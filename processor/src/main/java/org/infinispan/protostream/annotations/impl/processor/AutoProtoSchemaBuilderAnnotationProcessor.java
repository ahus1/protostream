package org.infinispan.protostream.annotations.impl.processor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.SerializationContextInitializer;
import org.infinispan.protostream.annotations.AutoProtoSchemaBuilder;
import org.infinispan.protostream.annotations.ProtoEnum;
import org.infinispan.protostream.annotations.ProtoEnumValue;
import org.infinispan.protostream.annotations.ProtoField;
import org.infinispan.protostream.annotations.ProtoMessage;
import org.infinispan.protostream.annotations.ProtoName;
import org.infinispan.protostream.annotations.ProtoSchemaBuilderException;
import org.infinispan.protostream.annotations.impl.IndentWriter;
import org.infinispan.protostream.annotations.impl.processor.types.HasModelElement;
import org.infinispan.protostream.annotations.impl.processor.types.MirrorClassFactory;
import org.infinispan.protostream.annotations.impl.types.XClass;
import org.infinispan.protostream.annotations.impl.types.XMethod;

import com.google.auto.service.AutoService;

@SupportedOptions(AutoProtoSchemaBuilderAnnotationProcessor.DEBUG_OPTION)
@SupportedAnnotationTypes(AutoProtoSchemaBuilderAnnotationProcessor.PROTO_SCHEMA_TOOL_ANNOTATION_NAME)
@AutoService(Processor.class)
public final class AutoProtoSchemaBuilderAnnotationProcessor extends AbstractProcessor {

   /**
    * The only option we support: activate debug logging.
    */
   public static final String DEBUG_OPTION = "debug";

   /**
    * The FQN of the one and only annotation we claim.
    */
   public static final String PROTO_SCHEMA_TOOL_ANNOTATION_NAME = "org.infinispan.protostream.annotations.AutoProtoSchemaBuilder";

   private ServiceLoaderFileGenerator serviceLoaderFileGenerator;

   private boolean debugEnabled;

   private Types types;

   private Elements elements;

   private Filer filer;

   private Messager messager;

   /**
    * An exception thrown to stop processing of annotations abruptly whenever the conditions do not allow to continue
    * (ie. annotation data is invalid).
    */
   private static final class AnnotationProcessingException extends RuntimeException {

      final Element location;
      final String message;
      final Object[] msgParams;

      AnnotationProcessingException(Element location, String message, Object... msgParams) {
         this.location = location;
         this.message = message;
         this.msgParams = msgParams;
      }

      AnnotationProcessingException(Throwable cause, Element location, String message, Object... msgParams) {
         super(cause);
         this.location = location;
         this.message = message;
         this.msgParams = msgParams;
      }
   }

   @Override
   public synchronized void init(ProcessingEnvironment processingEnv) {
      super.init(processingEnv);

      debugEnabled = processingEnv.getOptions().containsKey(DEBUG_OPTION);

      types = processingEnv.getTypeUtils();
      elements = processingEnv.getElementUtils();
      filer = processingEnv.getFiler();
      messager = processingEnv.getMessager();

      serviceLoaderFileGenerator = new ServiceLoaderFileGenerator(SerializationContextInitializer.class, filer);
   }

   @Override
   public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported(); //TODO [anistor] or maybe return processingEnv.getSourceVersion(), or hardcoded @SupportedSourceVersion(SourceVersion.RELEASE_8) ?
   }

   /**
    * Issue a compilation error.
    */
   private void reportError(Element e, String message, Object... msgParams) {
      String formatted = String.format(message, msgParams);
      if (e != null) {
         messager.printMessage(Diagnostic.Kind.ERROR, formatted, e);
      } else {
         messager.printMessage(Diagnostic.Kind.ERROR, formatted);
      }
   }

   /**
    * Issue a compilation warning.
    */
   private void reportWarning(Element e, String message, Object... msgParams) {
      String formatted = String.format(message, msgParams);
      if (e != null) {
         messager.printMessage(Diagnostic.Kind.WARNING, formatted, e);
      } else {
         messager.printMessage(Diagnostic.Kind.WARNING, formatted);
      }
   }

   /**
    * Log a debug message, only if debug option is enabled.
    */
   private void logDebug(String message, Object... msgParams) {
      if (debugEnabled) {
         String formatted = String.format(message, msgParams);
         messager.printMessage(Diagnostic.Kind.NOTE, formatted);
      }
   }

   @Override
   public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      try {
         boolean claimed = annotations.size() == 1 && annotations.iterator().next().getQualifiedName().contentEquals(PROTO_SCHEMA_TOOL_ANNOTATION_NAME);
         if (!claimed && !roundEnv.processingOver()) {
            return false;
         }

         for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(AutoProtoSchemaBuilder.class)) {
            AutoProtoSchemaBuilder builderAnnotation = annotatedElement.getAnnotation(AutoProtoSchemaBuilder.class);

            if (annotatedElement.getKind() != ElementKind.PACKAGE && annotatedElement.getKind() != ElementKind.INTERFACE && annotatedElement.getKind() != ElementKind.CLASS) {
               throw new AnnotationProcessingException(annotatedElement, "@AutoProtoSchemaBuilder can only be applied to classes, interfaces and packages.");
            }

            Collection<? extends TypeMirror> protoClasses = getClassesToProcess(roundEnv, annotatedElement, builderAnnotation);
            if (protoClasses.isEmpty()) {
               reportWarning(annotatedElement, "No ProtoStream annotated classes found matching the criteria. Please review the 'classes' / 'packages' attribute of the @AutoProtoSchemaBuilder annotation.");
            }

            try {
               processElement(annotatedElement, builderAnnotation, protoClasses);
            } catch (ProtoSchemaBuilderException e) {
               throw new AnnotationProcessingException(e, annotatedElement, "%s", e.getMessage());
            }
         }

         if (roundEnv.processingOver()) {
            serviceLoaderFileGenerator.generateResources();
         }
      } catch (AnnotationProcessingException e) {
         // this is caused by the user supplying incorrect data in the annotation or related classes
         reportError(e.location, e.message, e.msgParams);
         if (debugEnabled) {
            logDebug("@AutoProtoSchemaBuilder processor threw an exception: %s", getStackTraceAsString(e));
         }
      } catch (Exception e) {
         // this may be a fatal programming error in the annotation processor itself
         reportError(null, "@AutoProtoSchemaBuilder processor threw a fatal exception: %s", getStackTraceAsString(e));
      }

      return true;
   }

   private static String getStackTraceAsString(Throwable throwable) {
      StringWriter stringWriter = new StringWriter();
      throwable.printStackTrace(new PrintWriter(stringWriter));
      return stringWriter.toString();
   }

   /**
    * Gathers the message/enum classes to process and generate marshallers for.
    */
   private Collection<? extends TypeMirror> getClassesToProcess(RoundEnvironment roundEnv, Element builderElement, AutoProtoSchemaBuilder builderAnnotation) {
      Collection<? extends TypeMirror> specifiedClasses = Collections.emptyList();
      try {
         builderAnnotation.classes(); // this is guaranteed to fail
      } catch (MirroredTypesException mte) {
         specifiedClasses = mte.getTypeMirrors();
      }

      Map<String, TypeMirror> classes = new TreeMap<>();  // keep them sorted by FQN for predictable and repeatable order of processing

      if (specifiedClasses.isEmpty()) {
         // no explicit list of classes is specified so we gather all @ProtoXyz annotated classes from source path and filter them based on the specified packages

         Set<String> packages = builderAnnotation.packages().length == 0 ? null : new HashSet<>(builderAnnotation.packages().length);
         for (String p : builderAnnotation.packages()) {
            if (!SourceVersion.isName(p)) {
               throw new AnnotationProcessingException(builderElement, "@AutoProtoSchemaBuilder.packages contains and invalid package name : \"%s\"", p);
            }
            packages.add(p);
         }

         // message classes from unnamed package cannot be imported so they can only be used by initializers also located in unnamed package
         boolean initializerInUnnamedPackage = elements.getPackageOf(builderElement).isUnnamed();

         for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(ProtoField.class)) {
            Element enclosingElement = annotatedElement.getEnclosingElement();
            if (enclosingElement.getKind() == ElementKind.CLASS || enclosingElement.getKind() == ElementKind.INTERFACE) {
               TypeElement typeElement = (TypeElement) enclosingElement;
               filterByPackage(classes, typeElement, packages, initializerInUnnamedPackage);
            }
         }

         for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(ProtoEnumValue.class)) {
            Element enclosingElement = annotatedElement.getEnclosingElement();
            if (annotatedElement.getKind() != ElementKind.ENUM_CONSTANT || enclosingElement.getKind() != ElementKind.ENUM) {
               throw new AnnotationProcessingException(annotatedElement, "@ProtoEnumValue can only be applied to enum constants.");
            }
            TypeElement typeElement = (TypeElement) enclosingElement;
            filterByPackage(classes, typeElement, packages, initializerInUnnamedPackage);
         }

         for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(ProtoEnum.class)) {
            if (annotatedElement.getKind() != ElementKind.CLASS && annotatedElement.getKind() != ElementKind.INTERFACE) {
               throw new AnnotationProcessingException(annotatedElement, "@ProtoEnum can only be applied to enums.");
            }
            TypeElement typeElement = (TypeElement) annotatedElement;
            filterByPackage(classes, typeElement, packages, initializerInUnnamedPackage);
         }

         for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(ProtoMessage.class)) {
            if (annotatedElement.getKind() != ElementKind.CLASS && annotatedElement.getKind() != ElementKind.INTERFACE) {
               throw new AnnotationProcessingException(annotatedElement, "@ProtoMessage can only be applied to classes and interfaces.");
            }
            TypeElement typeElement = (TypeElement) annotatedElement;
            filterByPackage(classes, typeElement, packages, initializerInUnnamedPackage);
         }

         for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(ProtoName.class)) {
            if (annotatedElement.getKind() != ElementKind.CLASS && annotatedElement.getKind() != ElementKind.INTERFACE && annotatedElement.getKind() != ElementKind.ENUM) {
               throw new AnnotationProcessingException(annotatedElement, "@ProtoName can only be applied to classes, interfaces and enums.");
            }
            TypeElement typeElement = (TypeElement) annotatedElement.getEnclosingElement();
            filterByPackage(classes, typeElement, packages, initializerInUnnamedPackage);
         }
      } else {
         if (builderAnnotation.packages().length != 0) {
            throw new AnnotationProcessingException(builderElement, "@AutoProtoSchemaBuilder.packages cannot be specified unless @AutoProtoSchemaBuilder.classes is empty.");
         }

         // deduplicate the specified classes
         for (TypeMirror c : specifiedClasses) {
            TypeElement typeElement = (TypeElement) ((DeclaredType) c).asElement();
            String fqn = typeElement.getQualifiedName().toString();
            classes.putIfAbsent(fqn, c);
         }
      }

      return classes.values();
   }

   private void filterByPackage(Map<String, TypeMirror> collectedClasses, TypeElement typeElement, Set<String> packages, boolean initializerInUnnamedPackage) {
      // Skip interfaces and abstract classes when scanning packages. We only generate for instantiable classes.
      if (typeElement.getKind() == ElementKind.INTERFACE || typeElement.getModifiers().contains(Modifier.ABSTRACT)) {
         return;
      }

      PackageElement packageOfElement = elements.getPackageOf(typeElement);
      if (packageOfElement.isUnnamed() && !initializerInUnnamedPackage) {
         // classes from default package are ignored unless the initializer is itself in the default package
         return;
      }

      String fqn = typeElement.getQualifiedName().toString();

      if (packages != null) {
         String packageName = packageOfElement.getQualifiedName().toString();
         if (!isPackageIncluded(packages, packageName)) {
            return;
         }
      }

      collectedClasses.putIfAbsent(fqn, typeElement.asType());
   }

   /**
    * Checks if a package is included in a given set of packages, considering also their subpackages recursively.
    */
   private boolean isPackageIncluded(Set<String> packages, String packageName) {
      String p = packageName;

      while (true) {
         if (packages.contains(p)) {
            return true;
         }

         int pos = p.lastIndexOf('.');
         if (pos == -1) {
            break;
         }
         p = p.substring(0, pos);
      }

      return false;
   }

   private void processElement(Element annotatedElement, AutoProtoSchemaBuilder builderAnnotation, Collection<? extends TypeMirror> protoClasses) throws IOException {
      if (annotatedElement.getKind() == ElementKind.PACKAGE) {
         processPackage((PackageElement) annotatedElement, builderAnnotation, protoClasses);
      } else {
         processClass((TypeElement) annotatedElement, builderAnnotation, protoClasses);
      }
   }

   private void processPackage(PackageElement packageElement, AutoProtoSchemaBuilder annotation, Collection<? extends TypeMirror> classes) throws IOException {
      String initializerClassName = annotation.className();
      if (initializerClassName.isEmpty() || !SourceVersion.isIdentifier(initializerClassName) || SourceVersion.isKeyword(initializerClassName)) {
         throw new AnnotationProcessingException(packageElement, "@AutoProtoSchemaBuilder.className annotation attribute must be a valid Java identifier and must not be fully qualified.");
      }

      String packageName = packageElement.isUnnamed() ? null : packageElement.getQualifiedName().toString();
      String protobufPackageName = annotation.schemaPackageName().isEmpty() ? null : annotation.schemaPackageName();

      Set<String> deps = processDependencies(packageElement, annotation);

      MirrorClassFactory typeFactory = new MirrorClassFactory(processingEnv);

      Set<XClass> xclasses = classes.stream().map(typeFactory::fromTypeMirror).collect(Collectors.toCollection(LinkedHashSet::new));

      SerializationContext serCtx = ProtobufUtil.newSerializationContext();

      Set<String> generatedClasses = new LinkedHashSet<>();

      CompileTimeProtoSchemaGenerator protoSchemaGenerator = new CompileTimeProtoSchemaGenerator(typeFactory, processingEnv, serCtx,
            annotation.schemaFileName(), protobufPackageName, xclasses, annotation.autoImportClasses(), generatedClasses);
      String schemaSrc = protoSchemaGenerator.generateAndRegister();

      writeSerializationContextInitializerSourceFile(packageElement, packageElement.getQualifiedName().toString(), annotation,
            deps, classes, packageName, initializerClassName, annotation.schemaFileName(), schemaSrc, generatedClasses);
   }

   private void processClass(TypeElement typeElement, AutoProtoSchemaBuilder annotation, Collection<? extends TypeMirror> classes) throws IOException {
      if (typeElement.getNestingKind() == NestingKind.LOCAL || typeElement.getNestingKind() == NestingKind.ANONYMOUS) {
         throw new AnnotationProcessingException(typeElement, "Classes or interfaces annotated with @AutoProtoSchemaBuilder must not be local or anonymous.");
      }
      if (typeElement.getNestingKind() == NestingKind.MEMBER && !typeElement.getModifiers().contains(Modifier.STATIC)) {
         throw new AnnotationProcessingException(typeElement, "Nested classes or interfaces annotated with @AutoProtoSchemaBuilder must be static.");
      }
      if (typeElement.getModifiers().contains(Modifier.FINAL)) {
         throw new AnnotationProcessingException(typeElement, "Classes annotated with @AutoProtoSchemaBuilder must not be final.");
      }
      if (!annotation.className().isEmpty() && (!SourceVersion.isIdentifier(annotation.className()) || SourceVersion.isKeyword(annotation.className()))) {
         throw new AnnotationProcessingException(typeElement, "@AutoProtoSchemaBuilder.className annotation attribute must be a valid Java identifier and must not be fully qualified.");
      }

      if (!types.isSubtype(typeElement.asType(), elements.getTypeElement(SerializationContextInitializer.class.getName()).asType())) {
         throw new AnnotationProcessingException(typeElement, "Classes or interfaces annotated with @AutoProtoSchemaBuilder must implement/extend %s", SerializationContextInitializer.class.getName());
      }

      PackageElement packageElement = elements.getPackageOf(typeElement);
      String packageName = packageElement.isUnnamed() ? null : packageElement.getQualifiedName().toString();
      String initializerClassName = getInitializerClassName(typeElement, annotation);
      String protobufPackageName = annotation.schemaPackageName().isEmpty() ? null : annotation.schemaPackageName();

      Set<String> deps = processDependencies(typeElement, annotation);

      MirrorClassFactory typeFactory = new MirrorClassFactory(processingEnv);

      XClass annotatedType = typeFactory.fromTypeMirror(typeElement.asType());
      warnOverrideExistingMethod(annotatedType, "getProtoFileName");
      warnOverrideExistingMethod(annotatedType, "getProtoFile");
      XClass serializationContextClass = typeFactory.fromClass(SerializationContext.class);
      warnOverrideExistingMethod(annotatedType, "registerSchema", serializationContextClass);
      warnOverrideExistingMethod(annotatedType, "registerMarshallers", serializationContextClass);

      Set<XClass> xclasses = classes.stream().map(typeFactory::fromTypeMirror).collect(Collectors.toCollection(LinkedHashSet::new));

      SerializationContext serCtx = ProtobufUtil.newSerializationContext();

      Set<String> generatedClasses = new LinkedHashSet<>();

      CompileTimeProtoSchemaGenerator protoSchemaGenerator = new CompileTimeProtoSchemaGenerator(typeFactory, processingEnv, serCtx,
            annotation.schemaFileName(), protobufPackageName, xclasses, annotation.autoImportClasses(), generatedClasses);
      String schemaSrc = protoSchemaGenerator.generateAndRegister();

      writeSerializationContextInitializerSourceFile(typeElement, typeElement.getQualifiedName().toString(), annotation,
            deps, classes, packageName, initializerClassName, annotation.schemaFileName(), schemaSrc, generatedClasses);
   }

   private String getInitializerClassName(Element annotatedElement, AutoProtoSchemaBuilder builderAnnotation) {
      return builderAnnotation.className().isEmpty() ? annotatedElement.getSimpleName() + "Impl" : builderAnnotation.className();
   }

   private Set<String> processDependencies(Element annotatedElement, AutoProtoSchemaBuilder builderAnnotation) {
      List<? extends TypeMirror> dependencies = Collections.emptyList();
      try {
         builderAnnotation.dependsOn(); // this is guaranteed to fail
      } catch (MirroredTypesException mte) {
         dependencies = mte.getTypeMirrors();
      }

      TypeMirror serializationContextInitializer = elements.getTypeElement(SerializationContextInitializer.class.getName()).asType();

      Set<String> classNames = new LinkedHashSet<>(dependencies.size());
      for (TypeMirror dependencyType : dependencies) {
         TypeElement dependencyElement = (TypeElement) types.asElement(dependencyType);
         AutoProtoSchemaBuilder dependencyAnnotation = dependencyElement.getAnnotation(AutoProtoSchemaBuilder.class);
         if (dependencyAnnotation != null) {
            String initializerClassName = getInitializerClassName(dependencyElement, dependencyAnnotation);
            classNames.add(initializerClassName);
         } else {
            if (!types.isSubtype(dependencyType, serializationContextInitializer)) {
               throw new AnnotationProcessingException(annotatedElement, "Dependency %s must be annotated with @AutoProtoSchemaBuilder or must be an instantiable subtype of %s", dependencyElement.getQualifiedName().toString(), SerializationContextInitializer.class.getName());
            }
            ExecutableElement ctor = (ExecutableElement) dependencyElement.getEnclosedElements().stream()
                  .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR && ((ExecutableElement) e).getParameters().isEmpty())
                  .findFirst().orElse(null);
            if (ctor == null) {
               throw new AnnotationProcessingException(annotatedElement, "Dependency %s is not instantiable using a no-arguments constructor", dependencyElement.getQualifiedName().toString());
            }

            classNames.add(dependencyElement.getQualifiedName().toString());
         }
      }
      return classNames;
   }

   private void warnOverrideExistingMethod(XClass xclass, String methodName, XClass... argTypes) {
      XMethod method = xclass.getMethod(methodName, argTypes);
      if (method != null && !java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
         reportWarning(((HasModelElement) method).getElement(), "@AutoProtoSchemaBuilder will inadvertently override your %s.%s method.",
               method.getDeclaringClass().getName(), method.getName());
      }
   }

   private void writeSerializationContextInitializerSourceFile(Element annotatedElement, String annotatedElementFQN, AutoProtoSchemaBuilder annotation,
                                                               Set<String> deps,
                                                               Collection<? extends TypeMirror> classes,
                                                               String packageName, String initializerClassName,
                                                               String fileName, String schemaSrc,
                                                               Set<String> generatedClasses) throws IOException {
      String initializerFqn = packageName != null ? packageName + '.' + initializerClassName : initializerClassName;

      if (elements.getTypeElement(initializerFqn) != null) {
         logDebug("The class to be generated for \"%s\" already exists in source path: %s", annotatedElementFQN, initializerFqn);
      }

      Element[] originatingElements = new Element[classes.size() + 1];
      int i = 0;
      for (TypeMirror tm : classes) {
         originatingElements[i++] = types.asElement(tm);
      }
      originatingElements[i] = annotatedElement;

      if (!annotation.schemaFilePath().isEmpty()) {
         // write Protobuf schema as a resource file
         writeSchema(annotation.schemaFilePath(), fileName, schemaSrc, originatingElements, annotatedElement);
      }

      JavaFileObject initializerFile;
      try {
         initializerFile = filer.createSourceFile(initializerFqn, originatingElements);
      } catch (FilerException fe) {
         // duplicated class name maybe?
         throw new AnnotationProcessingException(fe, annotatedElement, "%s", fe.getMessage());
      }

      IndentWriter iw = new IndentWriter();
      iw.append("/*\n");
      iw.append(" Generated by ").append(getClass().getName()).append("\n");
      iw.append(annotatedElement.getKind() == ElementKind.PACKAGE ? " for package " : " for class ").append(annotatedElementFQN).append("\n");
      iw.append(" annotated with ").append(String.valueOf(annotation)).append("\n");
      iw.append(" */\n\n");

      if (packageName != null) {
         iw.append("package ").append(packageName).append(";\n\n");
      }

      addGeneratedBy(iw);

      iw.append("public class ").append(initializerClassName);
      if (annotatedElement.getKind() == ElementKind.PACKAGE) {
         iw.append(" implements ").append(SerializationContextInitializer.class.getName()).append(" {\n\n");
      } else {
         iw.append(annotatedElement.getKind() == ElementKind.INTERFACE ? " implements " : " extends ").append(annotatedElementFQN).append(" {\n\n");
      }
      iw.inc();

      if (annotation.schemaFilePath().isEmpty()) {
         iw.append("private static final String PROTO_SCHEMA = ").append(makeStringLiteral(schemaSrc)).append(";\n\n");
      }

      int k = 0;
      for (String s : deps) {
         iw.append("private final ").append(s).append(" dep").append(String.valueOf(k++)).append(" = new ").append(s).append("();\n\n");
      }

      iw.append("@Override\npublic String getProtoFileName() { return \"").append(fileName).append("\"; }\n\n");
      iw.append("@Override\npublic String getProtoFile() { return ");
      if (annotation.schemaFilePath().isEmpty()) {
         iw.append("PROTO_SCHEMA");
      } else {
         iw.append("org.infinispan.protostream.FileDescriptorSource.getResourceAsString(getClass(), getProtoFileName())");
      }
      iw.append("; }\n\n");

      iw.append("@Override\n");
      iw.append("public void registerSchema(org.infinispan.protostream.SerializationContext serCtx) throws java.io.IOException {\n");
      iw.inc();
      for (int j = 0; j < deps.size(); j++) {
         iw.append("dep").append(String.valueOf(j)).append(".registerSchema(serCtx);\n");
      }
      if (annotation.schemaFilePath().isEmpty()) {
         iw.append("serCtx.registerProtoFiles(org.infinispan.protostream.FileDescriptorSource.fromString(getProtoFileName(), PROTO_SCHEMA));\n");
      } else {
         String resourceFile = annotation.schemaFilePath().isEmpty() ? fileName : annotation.schemaFilePath().replace('.', '/') + '/' + fileName;
         iw.append("serCtx.registerProtoFiles(org.infinispan.protostream.FileDescriptorSource.fromResources(\"").append(resourceFile).append("\"));\n");
      }
      iw.dec();
      iw.append("}\n\n");

      iw.append("@Override\n");
      iw.append("public void registerMarshallers(org.infinispan.protostream.SerializationContext serCtx) {\n");
      iw.inc();
      for (int j = 0; j < deps.size(); j++) {
         iw.append("dep").append(String.valueOf(j)).append(".registerMarshallers(serCtx);\n");
      }
      for (String name : generatedClasses) {
         iw.append("serCtx.registerMarshaller(new ").append(name).append("());\n");
      }
      iw.dec();
      iw.append("}\n");

      iw.dec();
      iw.append("}\n");

      try (PrintWriter out = new PrintWriter(initializerFile.openWriter())) {
         out.print(iw.toString());
      }

      if (annotation.service()) {
         // generate META-INF/services entry for this initializer implementation
         serviceLoaderFileGenerator.addProvider(initializerFqn, annotatedElement);
      }
   }

   private void writeSchema(String filePath, String fileName, String schemaSrc, Element[] originatingElements, Element annotatedElement) throws IOException {
      // reinterpret the path as a package
      String pkg = filePath;
      if (pkg.startsWith("/")) {
         pkg = pkg.substring(1);
      }
      pkg = pkg.replace('/', '.');

      FileObject schemaFile;
      try {
         schemaFile = filer.createResource(StandardLocation.CLASS_OUTPUT, pkg, fileName, originatingElements);
      } catch (FilerException e) {
         throw new AnnotationProcessingException(e, annotatedElement, "Package %s already contains a resource file named %s", pkg, fileName);
      }

      try (PrintWriter out = new PrintWriter(schemaFile.openWriter())) {
         out.print(schemaSrc);
      }
   }

   static void addGeneratedBy(IndentWriter iw) {
      String ISO8601Date = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
      iw.append("@javax.annotation.Generated(value=\"").append(AutoProtoSchemaBuilderAnnotationProcessor.class.getName())
            .append("\", date=\"").append(ISO8601Date)
            .append("\",\n      comments=\"Please do not edit this file!\")\n");
   }

   private String makeStringLiteral(String s) {
      StringBuilder sb = new StringBuilder(s.length() + 2);
      sb.append('\"');
      for (int i = 0; i < s.length(); i++) {
         char ch = s.charAt(i);
         switch (ch) {
            case '\n':
               sb.append("\\n\" +\n\"");
               break;
            case '\"':
               sb.append("\\\"");
               break;
            case '\\':
               sb.append("\\\\");
               break;
            default:
               sb.append(ch);
         }
      }
      sb.append('\"');
      return sb.toString();
   }

   private String makeClassName(TypeElement e) {
      return makeNestedClassName(e, e.getSimpleName().toString());
   }

   private String makeNestedClassName(TypeElement e, String className) {
      Element enclosingElement = e.getEnclosingElement();
      if (enclosingElement instanceof PackageElement) {
         PackageElement packageElement = (PackageElement) enclosingElement;
         return packageElement.isUnnamed() ? className : packageElement.getQualifiedName() + "." + className;
      } else {
         TypeElement typeElement = (TypeElement) enclosingElement;
         return makeNestedClassName(typeElement, typeElement.getSimpleName() + "$" + className);
      }
   }
}