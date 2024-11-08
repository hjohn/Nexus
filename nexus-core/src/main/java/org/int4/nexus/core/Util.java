package org.int4.nexus.core;

class Util {

  static String makePretty(String text) {
    StringBuilder builder = new StringBuilder();
    String indent = "";
    boolean newLine = false;

    for(char c : text.toCharArray()) {
      if(c == '[') {
        indent += "  ";
        builder.append("[\n").append(indent);
      }
      else if(c == ']') {
        indent = indent.substring(0, indent.length() - 2);
        builder.append("\n").append(indent).append("]");
      }
      else if(c == '{') {
        indent += "  ";
        builder.append("{\n").append(indent);
      }
      else if(c == '}') {
        indent = indent.substring(0, indent.length() - 2);
        builder.append("\n").append(indent).append("}");
      }
      else if(c == ',') {
        builder.append(",\n").append(indent);
        newLine = true;
      }
      else if(c != ' ' || !newLine) {
        builder.append(c);
        newLine = false;
      }
    }

    return builder.toString();
  }
}
