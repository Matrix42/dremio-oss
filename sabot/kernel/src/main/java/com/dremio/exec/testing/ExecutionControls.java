/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.testing;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.arrow.util.VisibleForTesting;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.testing.InjectionSite.InjectionSiteKeyDeserializer;
import com.dremio.exec.util.AssertionUtil;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValue;
import com.dremio.options.OptionValue.OptionType;
import com.dremio.options.TypeValidators.TypeValidator;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Tracks the simulated controls that will be injected for testing purposes.
 */
public class ExecutionControls {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ExecutionControls.class);

  // used to map JSON specified injections to POJOs
  private static ObjectMapper controlsOptionMapper;

  @VisibleForTesting
  public static void setControlsOptionMapper(ObjectMapper objectMapper) {
    controlsOptionMapper = objectMapper;
    controlsOptionMapper.addMixInAnnotations(Injection.class, InjectionMixIn.class);
  }

  static {
    setControlsOptionMapper(new ObjectMapper());
  }

  // Jackson MixIn: an annotated class that is used only by Jackson's ObjectMapper to allow a list of injections to
  // hold various types of injections
  @JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type")
  @JsonSubTypes({
    @Type(value = ExceptionInjection.class, name = "exception"),
    @Type(value = CountDownLatchInjectionImpl.class, name = "latch"),
    @Type(value = PauseInjection.class, name = "pause")})
  public abstract static class InjectionMixIn {
  }

  /**
   * The JSON specified for the {@link com.dremio.exec.ExecConstants#NODE_CONTROL_INJECTIONS}
   * option is validated using this class. Controls are short-lived options.
   */
  public static class ControlsOptionValidator extends TypeValidator {

    private final int ttl; // the number of queries for which this option is valid

    /**
     * Constructor for controls option validator.
     *
     * @param name the name of the validator
     * @param def  the default JSON, specified as string
     * @param ttl  the number of queries for which this option should be valid
     */
    public ControlsOptionValidator(final String name, final String def, final int ttl) {
      super(name, OptionValue.Kind.STRING, OptionValue.createString(OptionType.SYSTEM, name, def));
      assert ttl > 0;
      this.ttl = ttl;
    }

    @Override
    public int getTtl() {
      return ttl;
    }

    @Override
    public boolean isShortLived() {
      return true;
    }

    @Override
    public void validate(final OptionValue v) {
      if (v.getType() != OptionType.SESSION) {
        throw UserException.validationError()
            .message("Controls can be set only at SESSION level.")
            .build(logger);
      }
      final String jsonString = v.getStringVal();
      try {
        validateControlsString(jsonString);
      } catch (final IOException e) {
        throw UserException.validationError()
            .message(String.format("Invalid controls option string (%s) due to %s.", jsonString, e.getMessage()))
            .build(logger);
      }
    }
  }

  /**
   * POJO used to parse JSON-specified controls.
   */
  @VisibleForTesting
  public static class Controls {
    public Collection<? extends Injection> injections;
  }

  public static void validateControlsString(final String jsonString) throws IOException {
    controlsOptionMapper.readValue(jsonString, Controls.class);
  }

  /**
   * The default value for controls.
   */
  public static final String DEFAULT_CONTROLS = "{}";

  /**
   * Caches the currently specified controls.
   */
  @JsonDeserialize(keyUsing = InjectionSiteKeyDeserializer.class)
  private final Map<InjectionSite, Injection> controls = new HashMap<>();

  private final NodeEndpoint endpoint; // the current endpoint

  public ExecutionControls(final OptionManager options, final NodeEndpoint endpoint) {
    this.endpoint = endpoint;

    if (!AssertionUtil.isAssertionsEnabled()) {
      return;
    }

    final OptionValue optionValue = options.getOption(ExecConstants.NODE_CONTROL_INJECTIONS);
    if (optionValue == null) {
      return;
    }

    final String opString = optionValue.getStringVal();
    final Controls controls;
    try {
      controls = controlsOptionMapper.readValue(opString, Controls.class);
    } catch (final IOException e) {
      // This never happens. opString must have been validated.
      logger.warn("Could not parse injections. Injections must have been validated before this point.");
      throw new RuntimeException("Could not parse injections.", e);
    }
    if (controls.injections == null) {
      return;
    }

    logger.debug("Adding control injections: \n{}", opString);
    for (final Injection injection : controls.injections) {
      this.controls.put(new InjectionSite(injection.getSiteClass(), injection.getDesc()), injection);
    }
  }

  /**
   * Look for an exception injection matching the given injector, site descriptor, and endpoint.
   *
   * @param injector the injector, which indicates a class
   * @param desc     the injection site description
   * @return the exception injection, if there is one for the injector, site and endpoint; null otherwise
   */
  public ExceptionInjection lookupExceptionInjection(final ExecutionControlsInjector injector, final String desc) {
    final Injection injection = lookupInjection(injector, desc);
    return injection != null ? (ExceptionInjection) injection : null;
  }

  /**
   * Look for an pause injection matching the given injector, site descriptor, and endpoint.
   *
   * @param injector the injector, which indicates a class
   * @param desc     the injection site description
   * @return the pause injection, if there is one for the injector, site and endpoint; null otherwise
   */
  public PauseInjection lookupPauseInjection(final ExecutionControlsInjector injector, final String desc) {
    final Injection injection = lookupInjection(injector, desc);
    return injection != null ? (PauseInjection) injection : null;
  }

  /**
   * Look for a count down latch injection matching the given injector, site descriptor, and endpoint.
   *
   * @param injector the injector, which indicates a class
   * @param desc     the injection site description
   * @return the count down latch injection, if there is one for the injector, site and endpoint;
   * otherwise, a latch that does nothing
   */
  public CountDownLatchInjection lookupCountDownLatchInjection(final ExecutionControlsInjector injector,
                                                               final String desc) {
    final Injection injection = lookupInjection(injector, desc);
    return injection != null ? (CountDownLatchInjection) injection : NoOpControlsInjector.LATCH;
  }

  private Injection lookupInjection(final ExecutionControlsInjector injector, final String desc) {
    if (controls.isEmpty()) {
      return null;
    }

    // lookup the request
    final InjectionSite site = new InjectionSite(injector.getSiteClass(), desc);
    final Injection injection = controls.get(site);
    if (injection == null) {
      return null;
    }
    // return only if injection was meant for this node
    return injection.isValidForBit(endpoint) ? injection : null;
  }

  /**
   * This method resumes all pauses within the current context (QueryContext or FragmentContext).
   */
  public void unpauseAll() {
    for (final Injection injection : controls.values()) {
      if (injection instanceof PauseInjection) {
        ((PauseInjection) injection).unpause();
      }
    }
  }
}
