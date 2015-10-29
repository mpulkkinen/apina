package fi.evident.apina.spring;

import fi.evident.apina.java.model.ClassMetadataCollection;
import fi.evident.apina.java.model.JavaClass;
import fi.evident.apina.java.model.JavaField;
import fi.evident.apina.java.model.JavaMethod;
import fi.evident.apina.java.model.type.*;
import fi.evident.apina.model.ApiDefinition;
import fi.evident.apina.model.ClassDefinition;
import fi.evident.apina.model.EnumDefinition;
import fi.evident.apina.model.PropertyDefinition;
import fi.evident.apina.model.settings.TranslationSettings;
import fi.evident.apina.model.type.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

import static fi.evident.apina.spring.NameTranslator.translateClassName;
import static fi.evident.apina.utils.PropertyUtils.propertyNameForGetter;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

/**
 * Translates Java types to model types.
 */
final class JacksonTypeTranslator {

    private final TranslationSettings settings;
    private final ClassMetadataCollection classes;
    private final TypeSchema schema;
    private final ApiDefinition api;

    /**
     * Maps translated simple names back to their original types.
     * Needed to make sure that our mapping remains unique.
     */
    private final Map<String,JavaBasicType> translatedNames = new HashMap<>();

    private static final Logger log = LoggerFactory.getLogger(JacksonTypeTranslator.class);

    private static final JavaBasicType JSON_IGNORE = new JavaBasicType("com.fasterxml.jackson.annotation.JsonIgnore");
    private static final JavaBasicType JSON_VALUE = new JavaBasicType("com.fasterxml.jackson.annotation.JsonValue");
    private static final List<JavaBasicType> OPTIONAL_NUMBER_TYPES =
            asList(new JavaBasicType(OptionalInt.class), new JavaBasicType(OptionalLong.class), new JavaBasicType(OptionalDouble.class));

    public JacksonTypeTranslator(TranslationSettings settings, ClassMetadataCollection classes, TypeSchema schema, ApiDefinition api) {
        this.settings = requireNonNull(settings);
        this.classes = requireNonNull(classes);
        this.schema = requireNonNull(schema);
        this.api = requireNonNull(api);
    }

    public ApiType translateType(JavaType javaType) {
        return javaType.accept(new JavaTypeVisitor<TypeSchema, ApiType>() {
            @Override
            public ApiType visit(JavaArrayType type, TypeSchema ctx) {
                return new ApiArrayType(translateType(type.getElementType()));
            }

            @Override
            public ApiType visit(JavaBasicType type, TypeSchema ctx) {
                if (classes.isInstanceOf(type, Collection.class)) {
                    return new ApiArrayType(ApiPrimitiveType.ANY);

                } else if (classes.isInstanceOf(type, Map.class)) {
                    return new ApiDictionaryType(ApiPrimitiveType.ANY);

                } else if (type.equals(new JavaBasicType(String.class))) {
                    return ApiPrimitiveType.STRING;

                } else if (classes.isNumber(type)) {
                    return ApiPrimitiveType.NUMBER;

                } else if (type.equals(JavaBasicType.BOOLEAN) || type.equals(new JavaBasicType(Boolean.class))) {
                    return ApiPrimitiveType.BOOLEAN;

                } else if (OPTIONAL_NUMBER_TYPES.contains(type)) {
                    return ApiPrimitiveType.NUMBER;

                } else if (type.equals(new JavaBasicType(Object.class))) {
                    return ApiPrimitiveType.ANY;

                } else if (type.isVoid()) {
                    return ApiPrimitiveType.VOID;

                } else {
                    return translateClassType(type);
                }
            }

            @Override
            public ApiType visit(JavaParameterizedType type, TypeSchema ctx) {
                JavaType baseType = type.getBaseType();
                List<JavaType> arguments = type.getArguments();

                if (classes.isInstanceOf(baseType, Collection.class) && arguments.size() == 1)
                    return new ApiArrayType(translateType(arguments.get(0)));
                else if (classes.isInstanceOf(baseType, Map.class) && arguments.size() == 2 && classes.isInstanceOf(arguments.get(0), String.class))
                    return new ApiDictionaryType(translateType(arguments.get(1)));
                else if (classes.isInstanceOf(baseType, Optional.class) && arguments.size() == 1)
                    return translateType(arguments.get(0));
                else
                    return translateType(baseType);
            }

            @Override
            public ApiType visit(JavaTypeVariable type, TypeSchema ctx) {
                List<JavaType> bounds = ctx.getTypeBounds(type);

                // TODO: merge the bounds instead of picking the first one
                if (!bounds.isEmpty())
                    return translateType(bounds.get(0));
                else
                    return ApiPrimitiveType.ANY;
            }

            @Override
            public ApiType visit(JavaWildcardType type, TypeSchema ctx) {
                return type.getLowerBound().map(JacksonTypeTranslator.this::translateType).orElse(ApiPrimitiveType.ANY);
            }

            @Override
            public ApiType visit(JavaInnerClassType type, TypeSchema ctx) {
                throw new UnsupportedOperationException("translating inner class types is not supported: " + type);
            }
        }, schema);
    }

    private ApiType translateClassType(JavaBasicType type) {
        ApiTypeName typeName = classNameForType(type);

        if (settings.isImported(typeName))
            return new ApiBlackBoxType(typeName);

        if (settings.isBlackBoxClass(type.getName()) || hasJsonValueAnnotation(type)) {
            log.debug("Translating {} as black box", type.getName());

            ApiBlackBoxType blackBoxType = new ApiBlackBoxType(typeName);
            api.addBlackBox(typeName);
            return blackBoxType;
        }

        ApiClassType classType = new ApiClassType(typeName);

        if (!api.containsType(typeName)) {
            JavaClass aClass = classes.findClass(type.getName()).orElse(null);
            if (aClass != null) {
                if (aClass.isEnum()) {
                    api.addEnumDefinition(new EnumDefinition(typeName, aClass.getEnumConstants()));

                } else {
                    ClassDefinition classDefinition = new ClassDefinition(typeName);

                    // We must first add the definition to api and only then proceed to
                    // initialize it because initialization of properties could refer
                    // back to this same class and we'd get infinite recursion if the
                    // class is not already installed.
                    api.addClassDefinition(classDefinition);
                    initClassDefinition(classDefinition, aClass);
                }
            }
        }

        return classType;
    }

    private ApiTypeName classNameForType(JavaBasicType type) {
        String translatedName = translateClassName(type.getName());

        JavaBasicType existingType = translatedNames.putIfAbsent(translatedName, type);
        if (existingType != null && !type.equals(existingType))
            throw new DuplicateClassNameException(type.getName(), existingType.getName());

        return new ApiTypeName(translatedName);
    }

    private boolean hasJsonValueAnnotation(JavaBasicType type) {
        return classes.findClass(type.getName()).map(cl -> cl.hasMethodWithAnnotation(JSON_VALUE)).orElse(false);
    }

    private void initClassDefinition(ClassDefinition classDefinition, JavaClass javaClass) {
        Set<String> ignoredProperties = ignoredProperties(javaClass);

        Predicate<String> acceptProperty = name -> !classDefinition.hasProperty(name) && !ignoredProperties.contains(name);

        for (JavaClass cl : classesUpwardsFrom(javaClass)) {
            addPropertiesFromGetters(cl, classDefinition, acceptProperty);
            addPropertiesFromFields(cl, classDefinition, acceptProperty);
        }
    }

    private void addPropertiesFromFields(JavaClass aClass, ClassDefinition classDefinition, Predicate<String> acceptProperty) {
        for (JavaField field : aClass.getPublicInstanceFields()) {
            String name = field.getName();
            if (acceptProperty.test(name)) {
                ApiType type = translateType(field.getType());
                classDefinition.addProperty(new PropertyDefinition(name, type));
            }
        }
    }

    private void addPropertiesFromGetters(JavaClass aClass, ClassDefinition classDefinition, Predicate<String> acceptProperty) {
        for (JavaMethod getter : aClass.getGetters()) {
            String name = propertyNameForGetter(getter.getName());
            if (acceptProperty.test(name)) {
                ApiType type = translateType(getter.getReturnType());
                classDefinition.addProperty(new PropertyDefinition(name, type));
            }
        }
    }

    private Set<String> ignoredProperties(JavaClass type) {
        Set<String> ignores = new HashSet<>();

        List<JavaClass> classes = classesUpwardsFrom(type);

        for (int i = classes.size() - 1; i >= 0; i--) {
            JavaClass aClass = classes.get(i);

            for (JavaField field : aClass.getPublicInstanceFields()) {
                if (field.hasAnnotation(JSON_IGNORE))
                    ignores.add(field.getName());
                else
                    ignores.remove(field.getName());
            }

            for (JavaMethod getter : aClass.getGetters()) {
                if (getter.hasAnnotation(JSON_IGNORE))
                    ignores.add(propertyNameForGetter(getter.getName()));
            }
        }

        return ignores;
    }

    private List<JavaClass> classesUpwardsFrom(JavaClass javaClass) {
        List<JavaClass> result = new ArrayList<>();

        for (JavaClass cl = javaClass; cl != null; cl = classes.findClass(cl.getSuperClass().getNonGenericClassName()).orElse(null))
            addClassAndInterfacesAt(cl, result);

        return result;
    }

    private void addClassAndInterfacesAt(JavaClass c, List<JavaClass> result) {
        if (result.contains(c))
            return;

        result.add(c);

        for (JavaType interfaceType : c.getInterfaces()) {
            JavaClass interfaceClass = classes.findClass(interfaceType.getNonGenericClassName()).orElse(null);
            if (interfaceClass != null)
                addClassAndInterfacesAt(interfaceClass, result);
        }
    }
}
