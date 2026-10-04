package com.minikun.visual;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Accepts only inert SVG drawing primitives before serving model output. */
final class SafeSvg {
    static final int MIN_HEIGHT = 480, MAX_HEIGHT = 3200;
    private static final Pattern CANVAS = Pattern.compile("viewBox=\"0 0 1200 (\\d+)\"");
    private static final Set<String> ELEMENTS = Set.of(
            "svg", "rect", "circle", "line", "polygon", "text");
    private static final Set<String> NUMERIC = Set.of(
            "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry",
            "width", "height", "stroke-width", "font-size", "letter-spacing",
            "opacity", "fill-opacity", "stroke-opacity");
    private static final Pattern NUMBER = Pattern.compile("-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");
    private static final Pattern COLOR = Pattern.compile("(?:#[0-9a-fA-F]{3,8}|[a-zA-Z]+)");
    private static final Pattern POINTS = Pattern.compile("[0-9+.,\\-\\s]+");
    private static final Pattern FACT_NUMBER = Pattern.compile("\\p{Nd}+(?:[.,]\\p{Nd}+)?%?");

    private SafeSvg() { }

    static byte[] sanitize(String source) {
        return sanitize(source, null);
    }

    static byte[] sanitize(String source, String factualSource) {
        if (source == null || source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > 128_000) {
            throw new ImageGenerationException("SVG has an invalid size");
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            var document = builder.parse(
                    new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
            Element root = document.getDocumentElement();
            if (!"svg".equals(root.getTagName())
                    || !"http://www.w3.org/2000/svg".equals(root.getAttribute("xmlns"))
                    || !"1200".equals(root.getAttribute("width"))
                    || !root.getAttribute("height").matches("[1-9][0-9]{2,3}")
                    || Integer.parseInt(root.getAttribute("height")) < MIN_HEIGHT
                    || Integer.parseInt(root.getAttribute("height")) > MAX_HEIGHT
                    || !("0 0 1200 " + root.getAttribute("height")).equals(root.getAttribute("viewBox"))) {
                throw new ImageGenerationException("SVG canvas is invalid");
            }
            var documentChildren = document.getChildNodes();
            for (int index = 0; index < documentChildren.getLength(); index++) {
                if (documentChildren.item(index) != root) {
                    throw new ImageGenerationException("SVG contains unsupported document content");
                }
            }
            validate(root);
            if (factualSource != null) {
                Set<String> supplied = new HashSet<>();
                var provided = FACT_NUMBER.matcher(factualSource);
                while (provided.find()) {
                    String value = normalizeNumber(provided.group());
                    supplied.add(value);
                    if (value.matches("[1-9]|10")) {
                        for (int ordinal = 1; ordinal <= Integer.parseInt(value); ordinal++) {
                            supplied.add(Integer.toString(ordinal));
                        }
                    }
                }
                validateNumbers(root, supplied);
            }
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString().getBytes(StandardCharsets.UTF_8);
        } catch (ImageGenerationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ImageGenerationException("SVG is invalid or unsafe", exception);
        }
    }

    /** Reads dimensions only from bytes already checked by sanitize. */
    static int canvasHeight(byte[] checked) {
        var canvas = CANVAS.matcher(new String(checked, StandardCharsets.UTF_8));
        if (!canvas.find()) throw new ImageGenerationException("SVG canvas is missing");
        return Integer.parseInt(canvas.group(1));
    }

    private static void validate(Element element) {
        if (!ELEMENTS.contains(element.getTagName())) {
            throw new ImageGenerationException("SVG contains an unsupported element");
        }
        var attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            String name = attribute.getNodeName();
            String value = attribute.getNodeValue();
            boolean allowed = NUMERIC.contains(name) && NUMBER.matcher(value).matches()
                    || ("fill".equals(name) || "stroke".equals(name)) && COLOR.matcher(value).matches()
                    || "points".equals(name) && POINTS.matcher(value).matches()
                    || "font-weight".equals(name) && value.matches("normal|bold|[1-9]00")
                    || "font-family".equals(name) && "sans-serif".equals(value)
                    || "text-anchor".equals(name) && value.matches("start|middle|end")
                    || "dominant-baseline".equals(name) && value.matches("auto|middle|central|hanging")
                    || "stroke-linecap".equals(name) && value.matches("butt|round|square")
                    || "stroke-linejoin".equals(name) && value.matches("miter|round|bevel")
                    || element.getParentNode() instanceof org.w3c.dom.Document
                            && ("xmlns".equals(name) && "http://www.w3.org/2000/svg".equals(value)
                            || "viewBox".equals(name) && value.equals("0 0 1200 " + element.getAttribute("height")));
            if (!allowed || value.length() > 10_000) {
                throw new ImageGenerationException("SVG contains an unsupported attribute");
            }
        }
        var children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element nested) {
                validate(nested);
            } else if (child.getNodeType() != Node.TEXT_NODE
                    || !"text".equals(element.getTagName())
                            && !child.getTextContent().isBlank()) {
                throw new ImageGenerationException("SVG contains unsupported content");
            }
        }
    }

    private static void validateNumbers(Node node, Set<String> supplied) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            var numbers = FACT_NUMBER.matcher(node.getTextContent());
            while (numbers.find()) {
                if (!supplied.contains(normalizeNumber(numbers.group()))) {
                    throw new ImageGenerationException("SVG contains a number absent from the request");
                }
            }
        }
        var children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            validateNumbers(children.item(index), supplied);
        }
    }

    private static String normalizeNumber(String value) {
        String suffix = value.endsWith("%") ? "%" : "";
        String digits = suffix.isEmpty() ? value : value.substring(0, value.length() - 1);
        return digits.replace(",", "").replaceAll("\\.0+$", "") + suffix;
    }
}
