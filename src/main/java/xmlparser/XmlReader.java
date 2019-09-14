package xmlparser;

import xmlparser.annotations.XmlAbstractClass;
import xmlparser.annotations.XmlNoImport;
import xmlparser.annotations.XmlPath;
import xmlparser.error.InvalidXPath;
import xmlparser.model.XmlElement;
import xmlparser.parsing.DomBuilder;
import xmlparser.parsing.ObjectDeserializer;
import xmlparser.utils.Interfaces.AccessDeserializers;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.*;

import static org.objenesis.ObjenesisHelper.newInstance;
import static xmlparser.model.XmlElement.findChildForName;
import static xmlparser.utils.Reflection.*;
import static xmlparser.xpath.XPathExpression.newXPath;

public interface XmlReader extends AccessDeserializers {

    default <T> T domToObject(final XmlElement node, final Class<T> clazz) throws InvalidXPath {
        if (node == null) return null;
        final ObjectDeserializer c = getDeserializer(clazz);
        if (c != null) return c.convert(node, clazz);

        final T o = newInstance(clazz);

        final String parentName = toName(clazz);
        XmlElement selectedNode;
        for (final Field f : listFields(clazz)) {
            f.setAccessible(true);
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.isAnnotationPresent(XmlNoImport.class)) continue;

            selectedNode = node;
            if (f.isAnnotationPresent(XmlPath.class)) {
                selectedNode = newXPath(parentName + "/" + f.getAnnotation(XmlPath.class).value()).evaluateAny(node);
            }

            switch (toFieldType(f)) {
                case FIELD_DESERIALIZER: setField(f, o, invokeFieldDeserializer(f, selectedNode)); break;
                case TEXTNODE: setField(f, o, textNodeToValue(f.getType(), selectedNode)); break;
                case ANNOTATED_ATTRIBUTE: setField(f, o, attributeToValue(f.getType(), toName(f), deWrap(selectedNode, f))); break;
                case SET: setField(f, o, domToSet(f, toClassOfCollection(f), toName(f), deWrap(selectedNode, f))); break;
                case LIST: setField(f, o, domToList(f, toClassOfCollection(f), toName(f), deWrap(selectedNode, f))); break;
                case ARRAY: setField(f, o, domToArray(f.getType().getComponentType(), toName(f), deWrap(selectedNode, f))); break;
                case MAP: setField(f, o, domToMap((ParameterizedType) f.getGenericType(), toName(f), deWrap(selectedNode, f))); break;
                default:
                    final String name = toName(f);
                    final String value = selectedNode.attributes.get(name);
                    if (value != null) {
                        setField(f, o, stringToValue(f.getType(), value));
                        break;
                    }
                    if (isAbstract(f)) {
                        final XmlElement child = selectedNode.findChildForName(name, null);
                        setField(f, o, domToObject(child, findAbstractType(f.getAnnotation(XmlAbstractClass.class), child)));
                        break;
                    }
                    setField(f, o, domToObject(findChildForName(deWrap(selectedNode, f),name, null), f.getType()));
                    break;
            }
        }
        return o;
    }

    default XmlElement deWrap(final XmlElement element, final Field field) {
        if (!isWrapped(field)) return element;
        return element.findChildForName(toWrappedName(field), null);
    }
    default Object textNodeToValue(final Class<?> type, final XmlElement node) {
        final ObjectDeserializer conv = getDeserializer(type);
        return (conv != null) ? conv.convert(node) : null;
    }
    default Object attributeToValue(final Class<?> type, final String name, final XmlElement node) {
        final ObjectDeserializer conv = getDeserializer(type);
        if (conv == null) return null;
        final String value = node.attributes.get(name);
        if (value == null) return null;
        return conv.convert(value);
    }
    default Object stringToValue(final Class<?> type, final String value) {
        final ObjectDeserializer conv = getDeserializer(type);
        return (conv != null) ? conv.convert(value) : null;
    }
    default Set<Object> domToSet(final Field field, final Class<?> type, final String name, final XmlElement node) throws InvalidXPath {
        if (node == null) return null;
        final ObjectDeserializer elementConv = getDeserializer(type);
        final boolean isAbstract = isAbstract(field);

        final Set<Object> set = new HashSet<>();
        for (final XmlElement n : node.children) {
            if (!n.name.equals(name)) continue;
            if (isAbstract) {
                set.add(domToObject(n, findAbstractType(field.getAnnotation(XmlAbstractClass.class), n)));
                continue;
            }

            set.add( (elementConv == null) ? domToObject(n, type) : elementConv.convert(n));
        }
        return set;
    }
    default List<Object> domToList(final Field field, final Class<?> type, final String name, final XmlElement node) throws InvalidXPath {
        if (node == null) return null;
        final ObjectDeserializer elementConv = getDeserializer(type);
        final boolean isAbstract = isAbstract(field);

        final List<Object> list = new LinkedList<>();
        for (final XmlElement n : node.children) {
            if (!n.name.equals(name)) continue;
            if (isAbstract) {
                list.add(domToObject(n, findAbstractType(field.getAnnotation(XmlAbstractClass.class), n)));
                continue;
            }

            list.add( (elementConv == null) ? domToObject(n, type) : elementConv.convert(n));
        }
        return list;
    }
    default Object[] domToArray(final Class<?> type, final String name, final XmlElement node) throws InvalidXPath {
        if (node == null) return null;
        final ObjectDeserializer elementConv = getDeserializer(type);

        final Object[] array = (Object[]) Array.newInstance(type, node.numChildrenWithName(name));
        int i = 0;
        for (final XmlElement n : node.children) {
            if (n.name.equals(name)) {
                array[i] = (elementConv == null) ? domToObject(n, type) : elementConv.convert(n, type);
                i++;
            }
        }
        return array;
    }
    default Map<Object, Object> domToMap(final ParameterizedType type, final String name, final XmlElement node) {
        if (node == null) return null;
        final XmlElement element = node.findChildForName(name, null);
        if (element == null) return null;

        final ObjectDeserializer convKey = getDeserializer(toClassOfMapKey(type));
        final ObjectDeserializer convVal = getDeserializer(toClassOfMapValue(type));

        final Map<Object, Object> map = new HashMap<>();
        for (final XmlElement child : element.children) {
            map.put(convKey.convert(child.name), convVal.convert(child));
        }
        return map;
    }

    static XmlElement toXmlDom(final InputStreamReader in) throws IOException {
        final DomBuilder p = new DomBuilder();
        XmlStreamReader.toXmlStream(in, p);
        return p.getRoot();
    }

}