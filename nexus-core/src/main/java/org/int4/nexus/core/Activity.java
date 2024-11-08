package org.int4.nexus.core;

import com.fasterxml.jackson.annotation.JsonIdentityReference;

import java.util.List;
import java.util.Map;

import org.int4.nexus.core.api.Action;

record Activity(
  String id,
  String description,
  @JsonIdentityReference(alwaysAsId = true) List<Device> participants,
  List<Action> setup,
  Map<String, Action> mappings
) {}
