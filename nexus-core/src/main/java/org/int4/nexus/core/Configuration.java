package org.int4.nexus.core;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.int4.nexus.core.api.Action;

record Configuration(
  Map<String, List<Map<String, Object>>> connectors,
  Map<String, List<Map<String, Object>>> handlers,
  List<Device> devices,
  @JsonProperty("input-mappings") Map<URI, Action> inputMappings,
  List<Activity> activities,
  Map<String, Template> templates
) {}
