/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

import org.languagetool.tools.RuleSaxParser;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.LocatorImpl;
import javax.xml.parsers.ParserConfigurationException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Persistent, checksummed SAX data for bundled grammar and disambiguation XML.
 * Only application resources enter this cache; submitted text never does.
 * Rule objects and native regex handles are rebuilt by the original handlers.
 */
public final class AndroidRuleCache {
    private static final int MAGIC = 0x4c544331;
    private static final int FORMAT = 2;
    private static final int HEADER = 40;
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static volatile File directory;
    private static final AtomicLong hits = new AtomicLong();
    private static final AtomicLong misses = new AtomicLong();

    private AndroidRuleCache() { }

    /** Caller supplies an app-version-specific directory, invalidating old rules. */
    public static void configure(File versionDirectory) {
        directory = versionDirectory;
    }

    public static long hits() { return hits.get(); }
    public static long misses() { return misses.get(); }

    public static <T extends DefaultHandler> T load(InputStream xml, String resource,
                                                   Supplier<T> factory) throws IOException, SAXException, ParserConfigurationException {
        File root = directory;
        File cached = root == null ? null : new File(root, hex(digest().digest(
                resource.getBytes(StandardCharsets.UTF_8))) + ".rules");
        if (cached != null && cached.isFile()) {
            try {
                verify(cached);
                T handler = factory.get();
                replay(cached, handler);
                hits.incrementAndGet();
                return handler;
            } catch (IOException | SAXException | RuntimeException invalidCache) {
                // A fresh handler is essential: replay may have modified the old one.
                cached.delete();
            }
        }
        misses.incrementAndGet();
        T handler = factory.get();
        File temporary = null;
        Recorder recorder = null;
        if (cached != null) {
            try {
                Files.createDirectories(root.toPath());
                temporary = File.createTempFile("rules-", ".tmp", root);
                recorder = new Recorder(temporary, handler);
            } catch (IOException unavailableStorage) {
                // Storage is an optimization; bundled rules remain usable without it.
            }
        }
        try {
            RuleSaxParser.create().parse(xml, recorder == null ? handler : recorder);
            if (recorder != null && recorder.finish()) {
                try {
                    Files.move(temporary.toPath(), cached.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException unavailableStorage) {
                    // Keep the successfully loaded language even if cache publication fails.
                }
            }
            return handler;
        } finally {
            if (recorder != null) recorder.close();
            if (temporary != null) temporary.delete();
        }
    }

    private static void verify(File file) throws IOException {
        if (file.length() < HEADER || file.length() > MAX_BYTES) throw new IOException("Cache size");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new IOException("Cache version");
            byte[] expected = new byte[32];
            in.readFully(expected);
            MessageDigest digest = digest();
            byte[] buffer = new byte[65536];
            for (int count; (count = in.read(buffer)) != -1;) digest.update(buffer, 0, count);
            if (!MessageDigest.isEqual(expected, digest.digest())) throw new IOException("Cache checksum");
        }
    }

    private static void replay(File file, DefaultHandler handler) throws IOException, SAXException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 65536))) {
            byte[] header = new byte[HEADER];
            in.readFully(header);
            List<String> strings = new ArrayList<>();
            LocatorImpl locator = new LocatorImpl();
            handler.setDocumentLocator(locator);
            handler.startDocument();
            while (true) {
                int event = in.readUnsignedByte();
                if (event == 0) {
                    if (in.read() != -1) throw new IOException("Trailing cache data");
                    handler.endDocument();
                    return;
                }
                locator.setLineNumber(in.readInt());
                locator.setColumnNumber(in.readInt());
                switch (event) {
                    case 1 -> {
                        String uri = readString(in, strings), local = readString(in, strings), name = readString(in, strings);
                        int count = in.readInt();
                        if (count < 0 || count > 256) throw new IOException("Cache attributes");
                        AttributesImpl attributes = new AttributesImpl();
                        for (int i = 0; i < count; i++) attributes.addAttribute(readString(in, strings),
                                readString(in, strings), readString(in, strings), readString(in, strings), readString(in, strings));
                        handler.startElement(uri, local, name, attributes);
                    }
                    case 2 -> handler.endElement(readString(in, strings), readString(in, strings), readString(in, strings));
                    case 3 -> {
                        char[] text = readString(in, strings).toCharArray();
                        handler.characters(text, 0, text.length);
                    }
                    default -> throw new IOException("Cache event");
                }
            }
        }
    }

    private static String readString(DataInputStream in, List<String> strings) throws IOException {
        int index = in.readInt();
        if (index >= 0) {
            if (index >= strings.size()) throw new IOException("Cache string reference");
            return strings.get(index);
        }
        int length = -index - 1;
        if (length > 2 * 1024 * 1024 || strings.size() >= 500000) throw new IOException("Cache string size");
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) chars[i] = in.readChar();
        String text = new String(chars);
        strings.add(text);
        return text;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(Character.forDigit((value >> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return text.toString();
    }

    private static final class Recorder extends DefaultHandler implements Closeable {
        private final File file;
        private final DefaultHandler delegate;
        private final MessageDigest checksum = digest();
        private final Map<String, Integer> strings = new HashMap<>();
        private DataOutputStream out;
        private Locator locator;
        private int exampleDepth;

        Recorder(File file, DefaultHandler delegate) throws IOException {
            this.file = file;
            this.delegate = delegate;
            FileOutputStream raw = new FileOutputStream(file);
            raw.write(new byte[HEADER]);
            out = new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(raw, checksum), 65536));
        }

        private void string(String value) throws IOException {
            Integer index = strings.get(value);
            if (index != null) out.writeInt(index);
            else {
                if (value.length() > 2 * 1024 * 1024 || strings.size() >= 500000) throw new IOException("Cache string size");
                out.writeInt(-value.length() - 1);
                // Preserve SAX chunks even if a supplementary character is split.
                out.writeChars(value);
                strings.put(value, strings.size());
            }
        }

        private void event(int type) throws IOException {
            out.writeByte(type);
            out.writeInt(locator == null ? -1 : locator.getLineNumber());
            out.writeInt(locator == null ? -1 : locator.getColumnNumber());
        }

        private void record(IOAction action) {
            if (out != null) {
                try { action.run(); }
                catch (IOException failure) { close(); }
            }
        }

        @Override public void setDocumentLocator(Locator value) { locator = value; delegate.setDocumentLocator(value); }
        @Override public InputSource resolveEntity(String publicId, String systemId) throws IOException, SAXException {
            return delegate.resolveEntity(publicId, systemId);
        }
        @Override public void startDocument() throws SAXException { delegate.startDocument(); }
        @Override public void endDocument() throws SAXException { record(() -> out.writeByte(0)); delegate.endDocument(); }
        @Override public void startElement(String uri, String local, String name, Attributes attributes) throws SAXException {
            // Examples are upstream test fixtures, never inputs to rule matching.
            // Avoid rebuilding and persisting their large strings on mobile.
            if (exampleDepth > 0 || name.equals("example")) { exampleDepth++; return; }
            record(() -> {
                event(1); string(uri); string(local); string(name); out.writeInt(attributes.getLength());
                for (int i = 0; i < attributes.getLength(); i++) {
                    string(attributes.getURI(i)); string(attributes.getLocalName(i)); string(attributes.getQName(i));
                    string(attributes.getType(i)); string(attributes.getValue(i));
                }
            });
            delegate.startElement(uri, local, name, attributes);
        }
        @Override public void endElement(String uri, String local, String name) throws SAXException {
            if (exampleDepth > 0) { exampleDepth--; return; }
            record(() -> { event(2); string(uri); string(local); string(name); });
            delegate.endElement(uri, local, name);
        }
        @Override public void characters(char[] text, int start, int length) throws SAXException {
            if (exampleDepth > 0) return;
            record(() -> { event(3); string(new String(text, start, length)); });
            delegate.characters(text, start, length);
        }
        @Override public void warning(SAXParseException failure) throws SAXException { delegate.warning(failure); }
        @Override public void error(SAXParseException failure) throws SAXException { delegate.error(failure); }
        @Override public void fatalError(SAXParseException failure) throws SAXException { delegate.fatalError(failure); }

        boolean finish() {
            if (out == null) return false;
            try {
                out.close();
                out = null;
                if (file.length() > MAX_BYTES) return false;
                try (RandomAccessFile header = new RandomAccessFile(file, "rw")) {
                    header.writeInt(MAGIC); header.writeInt(FORMAT); header.write(checksum.digest());
                    header.getFD().sync();
                }
                return true;
            } catch (IOException failure) { close(); return false; }
        }
        @Override public void close() {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) { }
                out = null;
            }
        }
    }

    @FunctionalInterface private interface IOAction { void run() throws IOException; }
}
