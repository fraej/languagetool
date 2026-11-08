/* LanguageTool, a natural language style checker
 * Copyright (C) 2026 LanguageTool contributors
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.languagetool.tools;

import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/** Creates rule parsers on both the desktop JDK and Android with bundled Xerces. */
public final class RuleSaxParser {
  private RuleSaxParser() {
  }

  public static SAXParser create() throws ParserConfigurationException, SAXException {
    SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
    parser.getXMLReader().setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    // These JDK-specific limits are not recognized by standalone Xerces. Do not
    // ignore other parser failures: they can prevent the rules from loading safely.
    for (String property : new String[]{"maxGeneralEntitySizeLimit", "totalEntitySizeLimit", "entityExpansionLimit"}) {
      try {
        parser.getXMLReader().setProperty("jdk.xml." + property, 0);
      } catch (SAXNotRecognizedException | SAXNotSupportedException ignored) {
        // Standalone Xerces has no corresponding JDK limit to lift.
      }
    }
    return parser;
  }
}
