package org.int4.nexus.core;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.int4.nexus.core.api.Action;
import org.int4.nexus.core.api.ActionHandler;

class TemplateHandler implements ActionHandler {
  private final Template template;
  private final Pattern pattern;

  public TemplateHandler(Template template) {
    this.template = template;
    this.pattern = Pattern.compile(template.template()
      .replaceAll("<(\\w+)>", "(?<$1>[^:]+)") // Match keys in angle brackets
    );
  }

  @Override
  public Optional<Action> handle(Action action) {
    String input = action.uri().getSchemeSpecificPart();
    Matcher m = pattern.matcher(input);

    if(!m.matches()) {
      throw new IllegalArgumentException("Action \"" + action.uri() + "\" did not match template \"" + template.template() + "\"");
    }

    String a = applyTemplatedParameters(m, template.action());
    String p = applyTemplatedParameters(m, template.payload());

    if(template.query() != null) {
      boolean first = true;

      for(Map.Entry<String, String> entry : template.query().entrySet()) {
        a += (first ? "?" : "&") + entry.getKey() + "=" + URLEncoder.encode(applyTemplatedParameters(m, entry.getValue()), StandardCharsets.UTF_8);
        first = false;
      }
    }

    return Optional.of(new Action(URI.create(a), template.method(), p));
  }

  private static String applyTemplatedParameters(Matcher m, String input) {
    if(input == null) {
      return null;
    }

    String templatedInput = input;

    for(String name : m.namedGroups().keySet()) {
      templatedInput = templatedInput.replace("<" + name + ">", m.group(name));
    }

    return templatedInput;
  }
}
