package Model.ExternalAPI;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Bounded, entity-free parser for the ECB's EUR-based daily reference feed. */
final class EcbDailyRates {
    static final int MAX_BYTES = 131072;
    private static final String ECB_NS = "http://www.ecb.int/vocabulary/2002-08-01/eurofxref";
    private static final String ENVELOPE_NS = "http://www.gesmes.org/xml/2002-08-01";
    private EcbDailyRates() { }

    static ExchangeRate.DailySnapshot parse(byte[] bytes, Instant fetchedAt) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES || fetchedAt == null) throw invalid();
        XMLStreamReader reader = null;
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> { throw new XMLStreamException("External entity refused"); });
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes), "UTF-8");
            Map<String, Double> eurRates = new LinkedHashMap<>();
            LocalDate date = null; int depth = 0, events = 0; boolean envelope = false, outerCube = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (++events > 2048 || event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw invalid();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 6 || reader.getAttributeCount() > 4) throw invalid();
                    String name = reader.getLocalName(), ns = reader.getNamespaceURI();
                    if (depth == 1) {
                        if (envelope || !"Envelope".equals(name) || !ENVELOPE_NS.equals(ns)) throw invalid();
                        envelope = true;
                    } else if ("Cube".equals(name)) {
                        if (!ECB_NS.equals(ns)) throw invalid();
                        if (depth == 2 && reader.getAttributeCount() == 0) {
                            if (outerCube) throw invalid(); outerCube = true;
                        } else if (depth == 3 && outerCube && reader.getAttributeCount() == 1 && date == null) {
                            String raw = reader.getAttributeValue(null, "time");
                            if (raw == null || !raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
                            date = LocalDate.parse(raw);
                            if (date.isAfter(fetchedAt.atOffset(ZoneOffset.UTC).toLocalDate()) || date.isBefore(LocalDate.of(1999, 1, 1))) throw invalid();
                        } else if (depth == 4 && date != null && reader.getAttributeCount() == 2) {
                            String code = reader.getAttributeValue(null, "currency"), raw = reader.getAttributeValue(null, "rate");
                            if (code == null || !code.matches("[A-Z]{3}") || code.equals("EUR") || eurRates.containsKey(code)
                                    || raw == null || raw.length() > 64 || !raw.matches("[0-9]+(?:\\.[0-9]+)?")) throw invalid();
                            double rate = Double.parseDouble(raw);
                            if (!Double.isFinite(rate) || rate <= 0 || !Double.isFinite(1.0 / rate) || eurRates.size() >= 200) throw invalid();
                            eurRates.put(code, rate);
                        } else throw invalid();
                    } else if (ECB_NS.equals(ns) || depth > 3 || outerCube && depth >= 3) throw invalid();
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (--depth < 0) throw invalid();
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && outerCube && depth >= 3 && !reader.isWhiteSpace()) throw invalid();
            }
            if (!envelope || !outerCube || depth != 0 || date == null) throw invalid();
            double usd = eurRates.getOrDefault("USD", 0.0);
            if (!Double.isFinite(usd) || usd <= 0) throw invalid();
            Map<String, Double> normalised = new LinkedHashMap<>();
            normalised.put("EUR", 1.0 / usd);
            for (var entry : eurRates.entrySet()) normalised.put(entry.getKey(), entry.getValue() / usd);
            normalised.put("USD", 1.0);
            Map<String, Double> valid = ExchangeRate.validatedRates(new org.json.JSONObject(normalised));
            // ECB supplies an as-of DATE, not an exact publication instant.
            return new ExchangeRate.DailySnapshot(valid, date.atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
                    "ecb", date.toString(), fetchedAt.toString());
        } catch (XMLStreamException | RuntimeException failure) {
            throw invalid();
        } finally {
            if (reader != null) try { reader.close(); } catch (XMLStreamException ignored) { }
        }
    }

    private static IOException invalid() { return new IOException("ECB reference snapshot is invalid"); }
}
