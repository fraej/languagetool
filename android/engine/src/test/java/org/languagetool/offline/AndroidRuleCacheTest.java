package org.languagetool.offline;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class AndroidRuleCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String XML = "<?xml version='1.0'?><!DOCTYPE rules [<!ENTITY word 'pel·lícula'>]>\n"
            + "<rules><rule id='accent'>Una &word; 😀.</rule></rules>";

    @After public void reset() { AndroidRuleCache.configure(null); }

    @Test public void restoresExpandedEntitiesUnicodeAttributesAndLocationsWithoutReadingXml() throws Exception {
        File root = temporary.newFolder();
        AndroidRuleCache.configure(root);
        Events cold = load(XML);
        long hits = AndroidRuleCache.hits();
        Events warm = AndroidRuleCache.load(new InputStream() {
            @Override public int read() { throw new AssertionError("Cached load must bypass XML"); }
        }, "/ca/grammar.xml", Events::new);
        assertEquals(cold.events, warm.events);
        assertEquals(cold.text.toString(), warm.text.toString());
        assertTrue(warm.text.toString().contains("pel·lícula 😀"));
        assertEquals(hits + 1, AndroidRuleCache.hits());
        assertEquals(1, root.listFiles().length);
    }

    @Test public void corruptCacheFallsBackToBundledXmlAndRepairsIt() throws Exception {
        File root = temporary.newFolder();
        AndroidRuleCache.configure(root);
        Events original = load(XML);
        File cache = root.listFiles()[0];
        byte[] bytes = Files.readAllBytes(cache.toPath());
        bytes[bytes.length / 2] ^= 0x7f;
        Files.write(cache.toPath(), bytes);
        assertEquals(original.events, load(XML).events);
        long hits = AndroidRuleCache.hits();
        assertEquals(original.events, load("not XML").events);
        assertEquals(hits + 1, AndroidRuleCache.hits());
    }

    @Test public void unavailableStorageStillLoadsLanguage() throws Exception {
        AndroidRuleCache.configure(temporary.newFile());
        assertTrue(load(XML).text.toString().contains("pel·lícula"));
    }

    @Test public void newAppVersionRebuildsFromUpdatedRules() throws Exception {
        AndroidRuleCache.configure(temporary.newFolder("v1"));
        load(XML);
        long hits = AndroidRuleCache.hits();
        AndroidRuleCache.configure(temporary.newFolder("v2"));
        assertTrue(load(XML.replace("pel·lícula", "cançó")).text.toString().contains("cançó"));
        assertEquals(hits, AndroidRuleCache.hits());
    }

    private static Events load(String xml) throws Exception {
        return AndroidRuleCache.load(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                "/ca/grammar.xml", Events::new);
    }

    private static final class Events extends DefaultHandler {
        final List<String> events = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        Locator locator;
        @Override public void setDocumentLocator(Locator value) { locator = value; }
        @Override public void startElement(String uri, String local, String name, Attributes attributes) {
            events.add("start:" + name + ":" + attributes.getValue("id") + ":" + locator.getLineNumber());
        }
        @Override public void endElement(String uri, String local, String name) { events.add("end:" + name); }
        @Override public void characters(char[] chars, int start, int length) { text.append(chars, start, length); }
    }
}
