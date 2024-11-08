package org.int4.nexus.core;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import java.util.List;
import java.util.Map;

import org.int4.nexus.core.api.Action;

@JsonIdentityInfo(property = "id", generator = ObjectIdGenerators.StringIdGenerator.class)
record Device(
  String id,
  List<Action> activation,
  List<Action> deactivation,
  Map<String, Action> mappings
) {}
