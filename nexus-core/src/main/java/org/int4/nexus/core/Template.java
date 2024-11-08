package org.int4.nexus.core;

import java.util.Map;

record Template(
  String template,
  String action,
  String method,
  String payload,
  Map<String, String> query
) {}
