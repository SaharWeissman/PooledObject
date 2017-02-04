package com.saharw.objectpool.codegen;


import com.google.common.base.CaseFormat;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.UnmodifiableIterator;
import com.saharw.objectpool.common.MoreElements;
import com.saharw.pooledobject.Pooled;
import com.saharw.pooledobject.PooledAdapter;
import com.saharw.pooledobject.PooledVersion;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.NameAllocator;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

@SupportedAnnotationTypes("com.saharw.pooledobject.Pooled")
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public final class PooledObjectProcessor extends AbstractProcessor {
    private ErrorReporter mErrorReporter;
    private Types mTypeUtils;
    private final String POOLED_CLASS_PREFIX = "Pooled_";
    private final String METHOD_PARAM_NAME = "o";
    private final String POOLED_OBJECT_PREFIX = "pool";
    private final String CREATE_METHOD_NAME = "create";
    private final String VALIDATE_METHOD_NAME = "validate";
    private final String EXPIRE_METHOD_NAME = "expire";
    private final String IS_VALID_METHOD = "isValid";

    static final class Property {
        final String fieldName;
        final VariableElement element;
        final TypeName typeName;
        final ImmutableSet<String> annotations;
        final int version;
        TypeMirror typeAdapter;

        Property(String fieldName, VariableElement element) {
            this.fieldName = fieldName;
            this.element = element;
            this.typeName = TypeName.get(element.asType());
            this.annotations = getAnnotations(element);

            // get the parcel adapter if any
            PooledAdapter pooledAdapter = element.getAnnotation(PooledAdapter.class);
            if (pooledAdapter != null) {
                try {
                    pooledAdapter.value();
                } catch (MirroredTypeException e) {
                    this.typeAdapter = e.getTypeMirror();
                }
            }

            // get the element version, default 0
            PooledVersion pooledVersion = element.getAnnotation(PooledVersion.class);
            this.version = pooledVersion == null ? 0 : pooledVersion.from();
        }

        public boolean isNullable() {
            return this.annotations.contains("Nullable");
        }

        public int version() {
            return this.version;
        }

        private ImmutableSet<String> getAnnotations(VariableElement element) {
            ImmutableSet.Builder<String> builder = ImmutableSet.builder();
            for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
                builder.add(annotation.getAnnotationType().asElement().getSimpleName().toString());
            }

            return builder.build();
        }
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        mErrorReporter = new ErrorReporter(processingEnv);
        mTypeUtils = processingEnv.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Collection<? extends Element> annotatedElements =
                roundEnv.getElementsAnnotatedWith(Pooled.class);
        List<TypeElement> types = new ImmutableList.Builder<TypeElement>()
                .addAll(ElementFilter.typesIn(annotatedElements))
                .build();

        for (TypeElement type : types) {
            processType(type);
        }

        // We are the only ones handling AutoParcel annotations
        return true;
    }

    private void processType(TypeElement type) {
        Pooled pooleObj = type.getAnnotation(Pooled.class);
        if (pooleObj == null) {
            mErrorReporter.abortWithError("annotation processor for @PooledObjects was invoked with a" +
                    "type annotated differently; compiler bug? O_o", type);
        }
        if (type.getKind() != ElementKind.CLASS) {
            mErrorReporter.abortWithError("@" + Pooled.class.getName() + " only applies to classes", type);
        }
        if (ancestorIsPooled(type)) {
            mErrorReporter.abortWithError("One @PooledObjects class shall not extend another", type);
        }

        checkModifiersIfNested(type);

        // get the fully-qualified class name
        String fqClassName = generatedSubclassName(type, 0);
        // class name
        String className = TypeUtil.simpleNameOf(fqClassName);
        String source = generateClass(type, className, type.getSimpleName().toString(), false);
        source = Reformatter.fixup(source);
        writeSourceFile(fqClassName, source, type);

    }

    private void writeSourceFile(String className, String text, TypeElement originatingType) {
        try {
            JavaFileObject sourceFile =
                    processingEnv.getFiler().createSourceFile(className, originatingType);
            Writer writer = sourceFile.openWriter();
            try {
                writer.write(text);
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            // This should really be an error, but we make it a warning in the hope of resisting Eclipse
            // bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=367599. If that bug manifests, we may get
            // invoked more than once for the same file, so ignoring the ability to overwrite it is the
            // right thing to do. If we are unable to write for some other reason, we should get a compile
            // error later because user code will have a reference to the code we were supposed to
            // generate (new AutoValue_Foo() or whatever) and that reference will be undefined.
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not write generated class " + className + ": " + e);
        }
    }

    private String generateClass(TypeElement type, String className, String classToExtend, boolean isFinal) {
        if (type == null) {
            mErrorReporter.abortWithError("generateClass was invoked with null type", type);
        }
        if (className == null) {
            mErrorReporter.abortWithError("generateClass was invoked with null class name", type);
        }
        if (classToExtend == null) {
            mErrorReporter.abortWithError("generateClass was invoked with null parent class", type);
        }
        List<VariableElement> nonPrivateFields = getParcelableFieldsOrError(type);
        if (nonPrivateFields.isEmpty()) {
            mErrorReporter.abortWithError("generateClass error, all fields are declared PRIVATE", type);
        }

        // get the properties
        ImmutableList<Property> properties = buildProperties(nonPrivateFields);

        // get the type adapters
        ImmutableMap<TypeMirror, FieldSpec> typeAdapters = getTypeAdapters(properties);

        // get the parcel version
        //noinspection ConstantConditions
        int version = type.getAnnotation(Pooled.class).version();

        // Generate the Pooled_??? class
        String pkg = TypeUtil.packageNameOf(type);
        TypeName classTypeName = ClassName.get(pkg, className);
        TypeSpec.Builder subClass = TypeSpec.classBuilder(className)
                // Add the version
                .addField(TypeName.INT, "version", PRIVATE)
                // Class must be always final
                .addModifiers(FINAL)
                // extends from original abstract class
                .superclass(ClassName.get(pkg, classToExtend))
                // Add the DEFAULT constructor
                .addMethod(generateConstructor(properties))
                // Add the private constructor
                .addMethod(generateConstructorFromObject(className, processingEnv, properties, typeAdapters))
                // overrides describeContents()
                // static final Pooled
                .addField(generatePooled(className, processingEnv, properties, classTypeName, typeAdapters));
                // overrides writeToParcel()

//        if (!ancestoIsParcelable(processingEnv, type)) {
//            // Implement android.os.Parcelable if the ancestor does not do it.
//            subClass.addSuperinterface(ClassName.get("android.os", "Parcelable"));
//        }

        if (!typeAdapters.isEmpty()) {
            for(int i = 0; i < typeAdapters.values().size(); i++){
                UnmodifiableIterator<FieldSpec> iterator =  typeAdapters.values().iterator();
                while(iterator.hasNext()){
                    subClass.addField(iterator.next());
                }
            }
        }
        JavaFile javaFile = JavaFile.builder(pkg, subClass.build()).build();
        return javaFile.toString();
    }


    private boolean ancestorIsPooled(TypeElement type) {
        while (true) {
            TypeMirror parentMirror = type.getSuperclass();
            if (parentMirror.getKind() == TypeKind.NONE) {
                return false;
            }
            TypeElement parentElement = (TypeElement) mTypeUtils.asElement(parentMirror);
            if (MoreElements.isAnnotationPresent(parentElement, Pooled.class)) {
                return true;
            }
            type = parentElement;
        }
    }

    private void checkModifiersIfNested(TypeElement type) {
        ElementKind enclosingKind = type.getEnclosingElement().getKind();
        if (enclosingKind.isClass() || enclosingKind.isInterface()) {
            if (type.getModifiers().contains(PRIVATE)) {
                mErrorReporter.abortWithError("@PooledObjects class must not be private", type);
            }
            if (!type.getModifiers().contains(STATIC)) {
                mErrorReporter.abortWithError("Nested @PooledObjects class must be static", type);
            }
        }
        // In principle type.getEnclosingElement() could be an ExecutableElement (for a class
        // declared inside a method), but since RoundEnvironment.getElementsAnnotatedWith doesn't
        // return such classes we won't see them here.
    }

    private String generatedSubclassName(TypeElement type, int depth) {
        return generatedClassName(type, Strings.repeat("$", depth) + POOLED_CLASS_PREFIX);
    }

    private String generatedClassName(TypeElement type, String prefix) {
        String name = type.getSimpleName().toString();
        while (type.getEnclosingElement() instanceof TypeElement) {
            type = (TypeElement) type.getEnclosingElement();
            name = type.getSimpleName() + "_" + name;
        }
        String pkg = TypeUtil.packageNameOf(type);
        String dot = (pkg == null || pkg.equals("")) ? "" : ".";
        return pkg + dot + prefix + name;
    }

    /**
     * This method returns a list of all non private fields. If any <code>private</code> fields is
     * found, the method errors out
     *
     * @param type element
     * @return list of all non-<code>private</code> fields
     */
    private List<VariableElement> getParcelableFieldsOrError(TypeElement type) {
        List<VariableElement> allFields = ElementFilter.fieldsIn(type.getEnclosedElements());
        List<VariableElement> nonPrivateFields = new ArrayList<>();

        for (VariableElement field : allFields) {
            if (!field.getModifiers().contains(PRIVATE)) {
                nonPrivateFields.add(field);
            } else {
                // return error, PRIVATE fields are not allowed
                mErrorReporter.abortWithError("getFieldsError error, PRIVATE fields not allowed", type);
            }
        }

        return nonPrivateFields;
    }

    private ImmutableList<Property> buildProperties(List<VariableElement> elements) {
        ImmutableList.Builder<Property> builder = ImmutableList.builder();
        for (VariableElement element : elements) {
            builder.add(new Property(element.getSimpleName().toString(), element));
        }

        return builder.build();
    }

    private MethodSpec generateConstructor(ImmutableList<Property> properties) {

        List<ParameterSpec> params = Lists.newArrayListWithCapacity(properties.size());
        for (Property property : properties) {
            params.add(ParameterSpec.builder(property.typeName, property.fieldName).build());
        }

        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addParameters(params);

        for (ParameterSpec param : params) {
            builder.addStatement("this.$N = $N", param.name, param.name);
        }

        return builder.addModifiers(PUBLIC).build();
    }

    private ImmutableMap<TypeMirror, FieldSpec> getTypeAdapters(ImmutableList<Property> properties) {
        Map<TypeMirror, FieldSpec> typeAdapters = new LinkedHashMap<>();
        NameAllocator nameAllocator = new NameAllocator();
        nameAllocator.newName("PooledObjects");
        for (Property property : properties) {
            if (property.typeAdapter != null && !typeAdapters.containsKey(property.typeAdapter)) {
                ClassName typeName = (ClassName) TypeName.get(property.typeAdapter);
                String name = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, typeName.simpleName());
                name = nameAllocator.newName(name, typeName);

                typeAdapters.put(property.typeAdapter, FieldSpec.builder(
                        typeName, NameAllocator.toJavaIdentifier(name), PRIVATE, STATIC, FINAL)
                        .initializer("new $T()", typeName).build());
            }
        }
        return ImmutableMap.copyOf(typeAdapters);
    }

    private MethodSpec generateConstructorFromObject(
            String className, ProcessingEnvironment env,
            ImmutableList<Property> properties,
            ImmutableMap<TypeMirror, FieldSpec> typeAdapters) {

        // Create constructor from Object
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addModifiers(PUBLIC)      // private
                .addParameter(ClassName.OBJECT, METHOD_PARAM_NAME); // input param

        // get a code block builder
        CodeBlock.Builder block = CodeBlock.builder();

        // FIXME: 31/07/16 remove if not used
        boolean requiresSuppressWarnings = false;

        // Now, iterate all properties, check the version initialize them
        for (Property p : properties) {

            // get the property version
            int pVersion = p.version();
            if (pVersion > 0) {
                block.beginControlFlow("if (this.version >= $L)", pVersion);
            }

            block.add("this.$N = ", p.fieldName);

            if (p.typeAdapter != null && typeAdapters.containsKey(p.typeAdapter)) {
                PooledObjects.readValueWithTypeAdapter(className, block, p, typeAdapters.get(p.typeAdapter));
            } else {
                requiresSuppressWarnings |= PooledObjects.isTypeRequiresSuppressWarnings(p.typeName);
                TypeName parcelableType = PooledObjects.getTypeNameFromProperty(p, env.getTypeUtils());
                PooledObjects.readValue(className, block, p, parcelableType);
            }

            block.add(";\n");

            if (pVersion > 0) {
                block.endControlFlow();
            }
        }

        builder.addCode(block.build());

        return builder.build();
    }

//    private MethodSpec generateConstructorFromParcel(
//            ProcessingEnvironment env,
//            ImmutableList<Property> properties,
//            ImmutableMap<TypeMirror, FieldSpec> typeAdapters) {
//
//        // Create the PRIVATE constructor from Parcel
//        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
//                .addModifiers(PRIVATE)      // private
//                .addParameter(ClassName.bestGuess("Java.lang.Object"), "object"); // input param
//
//        // get a code block builder
//        CodeBlock.Builder block = CodeBlock.builder();
//
//        // First thing is reading the Parcelable object version
//        block.add("this.version = in.readInt();\n");
//
//        // FIXME: 31/07/16 remove if not used
//        boolean requiresSuppressWarnings = false;
//
//        // Now, iterate all properties, check the version initialize them
//        for (Property p : properties) {
//
//            // get the property version
//            int pVersion = p.version();
//            if (pVersion > 0) {
//                block.beginControlFlow("if (this.version >= $L)", pVersion);
//            }
//
//            block.add("this.$N = ", p.fieldName);
//
//            if (p.typeAdapter != null && typeAdapters.containsKey(p.typeAdapter)) {
//                PooledObjects.readValueWithTypeAdapter(className, block, p, typeAdapters.get(p.typeAdapter));
//            } else {
//                requiresSuppressWarnings |= PooledObjects.isTypeRequiresSuppressWarnings(p.typeName);
//                TypeName parcelableType = PooledObjects.getTypeNameFromProperty(p, env.getTypeUtils());
//                PooledObjects.readValue(className, block, p, parcelableType);
//            }
//
//            block.add(";\n");
//
//            if (pVersion > 0) {
//                block.endControlFlow();
//            }
//        }
//
//        builder.addCode(block.build());
//
//        return builder.build();
//    }

    private FieldSpec generatePooled(
            String className, ProcessingEnvironment env,
            ImmutableList<Property> properties,
            TypeName type,
            ImmutableMap<TypeMirror, FieldSpec> typeAdapters) {
        ClassName creator = ClassName.bestGuess("com.saharw.pooledobject.PooledObject");
        TypeName creatorOfClass = ParameterizedTypeName.get(creator, TypeVariableName.get(className.replaceFirst(POOLED_CLASS_PREFIX, "")));
        CodeBlock.Builder ctorCall = CodeBlock.builder();
        ctorCall.add("return new $T(" + METHOD_PARAM_NAME + ");\n", creatorOfClass);

        // Method create()
        MethodSpec.Builder createMethod = MethodSpec.methodBuilder(CREATE_METHOD_NAME);
        createMethod
                .addAnnotation(Override.class)
                .addModifiers(PUBLIC)
                .addParameter(TypeVariableName.get(className.replaceFirst(POOLED_CLASS_PREFIX, "")), METHOD_PARAM_NAME)
                .returns(TypeVariableName.get(className.replaceFirst(POOLED_CLASS_PREFIX, "")));
        createMethod.addCode(generateCreateCode(className, type));

        //Method validate(T o)
        MethodSpec.Builder validateMethod = MethodSpec.methodBuilder(VALIDATE_METHOD_NAME);
        validateMethod
                .addAnnotation(Override.class)
                .addModifiers(PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(TypeVariableName.get(className.replaceFirst(POOLED_CLASS_PREFIX, "")), METHOD_PARAM_NAME);
        validateMethod.addCode(generateValidateCode(className, type));

        //Method expire(T o)
        MethodSpec.Builder expireMethod = MethodSpec.methodBuilder(EXPIRE_METHOD_NAME);
        expireMethod
                .addAnnotation(Override.class)
                .addModifiers(PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(TypeVariableName.get(className.replaceFirst(POOLED_CLASS_PREFIX, "")), METHOD_PARAM_NAME);
        expireMethod.addCode(generateExpireCode(className, type));

        TypeSpec pooledImpl = TypeSpec.anonymousClassBuilder("")
                .superclass(creatorOfClass)
                .addMethod(createMethod.returns(TypeVariableName.get(className.replaceFirst(POOLED_CLASS_PREFIX, "")))
                        .build())
                .addMethod(validateMethod.returns(TypeName.BOOLEAN)
                        .build())
                .addMethod(expireMethod.returns(TypeName.VOID).build())
                .build();

        return FieldSpec
                .builder(creator, POOLED_OBJECT_PREFIX, PUBLIC, FINAL, STATIC)
                .initializer("$L", pooledImpl)
                .build();
    }

    private CodeBlock generateValidateCode(String className, TypeName type) {
        CodeBlock.Builder codeBlock = CodeBlock.builder();
//        try
//        {
//            return( ! ( ( Connection ) o ).isClosed() );
//        }
//        catch( SQLException e )
//        {
//            e.printStackTrace();
//            return( false );
//        }
        codeBlock.add("return " + METHOD_PARAM_NAME + "." + IS_VALID_METHOD + "();");
        return codeBlock.build();
    }

    private CodeBlock generateCreateCode(String className, TypeName type) {
        CodeBlock.Builder codeBlock = CodeBlock.builder();

//        generates:
//          return new <Orig_obj_type>(origObjInstanceToClone);
        String origType = className.replaceFirst(POOLED_CLASS_PREFIX, "");
        codeBlock.addStatement("return new " + origType +"(" + METHOD_PARAM_NAME + ")");
        return codeBlock.build();
    }

    private CodeBlock generateExpireCode(String className, TypeName type) {
        CodeBlock.Builder codeBlock = CodeBlock.builder();

        codeBlock.addStatement(METHOD_PARAM_NAME + "." + EXPIRE_METHOD_NAME + "()");
        return codeBlock.build();
    }

    private MethodSpec generatePooled(
            int version,
            ProcessingEnvironment env,
            ImmutableList<Property> properties,
            ImmutableMap<TypeMirror, FieldSpec> typeAdapters) {
        ParameterSpec dest = ParameterSpec
                .builder(ClassName.get("com.saharw.pooledobject.entities", "PooledObject"), "pooled")
                .build();
        ParameterSpec flags = ParameterSpec.builder(int.class, "flags").build();
        MethodSpec.Builder builder = MethodSpec.methodBuilder("pooled")
                .addAnnotation(Override.class)
                .addModifiers(PUBLIC)
                .addParameter(dest)
                .addParameter(flags);

        // write first the parcelable object version...
        builder.addCode(PooledObjects.writeVersion(version, dest));

        // ...then write all the properties
        for (Property p : properties) {
            if (p.typeAdapter != null && typeAdapters.containsKey(p.typeAdapter)) {
                FieldSpec typeAdapter = typeAdapters.get(p.typeAdapter);
                builder.addCode(PooledObjects.writeValueWithTypeAdapter(typeAdapter, p, dest));
            } else {
                builder.addCode(PooledObjects.writeValue(p, dest, flags, env.getTypeUtils()));
            }
        }

        return builder.build();
    }

    private static AnnotationSpec createSuppressUncheckedWarningAnnotation() {
        return AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "\"unchecked\"")
                .build();
    }
}
