package io.quarkiverse.langchain4j.deployment;

import static dev.langchain4j.exception.IllegalConfigurationException.illegalConfiguration;
import static dev.langchain4j.service.ServiceOutputParser.outputFormatInstructions;
import static io.quarkiverse.langchain4j.deployment.ExceptionUtil.illegalConfigurationForMethod;
import static io.quarkiverse.langchain4j.deployment.Langchain4jDotNames.NO_RETRIEVER;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.MethodParameterInfo;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import dev.langchain4j.exception.IllegalConfigurationException;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.deployment.items.SelectedChatModelProviderBuildItem;
import io.quarkiverse.langchain4j.runtime.AiServicesRecorder;
import io.quarkiverse.langchain4j.runtime.NamedModelUtil;
import io.quarkiverse.langchain4j.runtime.aiservice.AiServiceClassCreateInfo;
import io.quarkiverse.langchain4j.runtime.aiservice.AiServiceMethodCreateInfo;
import io.quarkiverse.langchain4j.runtime.aiservice.AiServiceMethodImplementationSupport;
import io.quarkiverse.langchain4j.runtime.aiservice.ChatMemoryRemovable;
import io.quarkiverse.langchain4j.runtime.aiservice.DeclarativeAiServiceCreateInfo;
import io.quarkiverse.langchain4j.runtime.aiservice.MetricsCountedWrapper;
import io.quarkiverse.langchain4j.runtime.aiservice.MetricsTimedWrapper;
import io.quarkiverse.langchain4j.runtime.aiservice.QuarkusAiServiceContext;
import io.quarkiverse.langchain4j.runtime.aiservice.SpanWrapper;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanGizmoAdaptor;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.processor.ScopeInfo;
import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.metrics.MetricsCapabilityBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.Gizmo;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.runtime.metrics.MetricsFactory;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class AiServicesProcessor {

    private static final Logger log = Logger.getLogger(AiServicesProcessor.class);

    private static final DotName V = DotName.createSimple(V.class);
    public static final DotName MICROMETER_TIMED = DotName.createSimple("io.micrometer.core.annotation.Timed");
    public static final DotName MICROMETER_COUNTED = DotName.createSimple("io.micrometer.core.annotation.Counted");
    private static final String DEFAULT_DELIMITER = "\n";
    private static final Predicate<AnnotationInstance> IS_METHOD_PARAMETER_ANNOTATION = ai -> ai.target()
            .kind() == AnnotationTarget.Kind.METHOD_PARAMETER;
    private static final Function<AnnotationInstance, Integer> METHOD_PARAMETER_POSITION_FUNCTION = ai -> Integer
            .valueOf(ai.target()
                    .asMethodParameter().position());

    public static final MethodDescriptor OBJECT_CONSTRUCTOR = MethodDescriptor.ofConstructor(Object.class);
    private static final MethodDescriptor RECORDER_METHOD_CREATE_INFO = MethodDescriptor.ofMethod(AiServicesRecorder.class,
            "getAiServiceMethodCreateInfo", AiServiceMethodCreateInfo.class, String.class, String.class);
    private static final MethodDescriptor SUPPORT_IMPLEMENT = MethodDescriptor.ofMethod(
            AiServiceMethodImplementationSupport.class,
            "implement", Object.class, AiServiceMethodImplementationSupport.Input.class);

    private static final MethodDescriptor QUARKUS_AI_SERVICES_CONTEXT_CLOSE = MethodDescriptor.ofMethod(
            QuarkusAiServiceContext.class, "close", void.class);

    private static final MethodDescriptor QUARKUS_AI_SERVICES_CONTEXT_REMOVE_CHAT_MEMORY_IDS = MethodDescriptor.ofMethod(
            QuarkusAiServiceContext.class, "removeChatMemoryIds", void.class, Object[].class);
    public static final DotName CDI_INSTANCE = DotName.createSimple(Instance.class);
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final String METRICS_DEFAULT_NAME = "langchain4j.aiservices";
    public static final ClassType CHAT_MODEL_CLASS_TYPE = ClassType.create(Langchain4jDotNames.CHAT_MODEL);

    @BuildStep
    public void nativeSupport(CombinedIndexBuildItem indexBuildItem,
            List<AiServicesMethodBuildItem> aiServicesMethodBuildItems,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClassProducer) {
        IndexView index = indexBuildItem.getIndex();
        Collection<AnnotationInstance> instances = index.getAnnotations(Langchain4jDotNames.DESCRIPTION);
        Set<ClassInfo> classesUsingDescription = new HashSet<>();
        for (AnnotationInstance instance : instances) {
            if (instance.target().kind() != AnnotationTarget.Kind.FIELD) {
                continue;
            }
            classesUsingDescription.add(instance.target().asField().declaringClass());
        }
        if (!classesUsingDescription.isEmpty()) {
            reflectiveClassProducer.produce(ReflectiveClassBuildItem
                    .builder(classesUsingDescription.stream().map(i -> i.name().toString()).toArray(String[]::new)).fields(true)
                    .build());
        }
        Set<DotName> returnTypesToRegister = new HashSet<>();
        for (AiServicesMethodBuildItem aiServicesMethodBuildItem : aiServicesMethodBuildItems) {
            Type type = aiServicesMethodBuildItem.methodInfo.returnType();
            if (type.kind() == Type.Kind.PRIMITIVE) {
                continue;
            }
            DotName returnTypeName = type.name();
            if (returnTypeName.toString().startsWith("java.")) {
                continue;
            }
            returnTypesToRegister.add(returnTypeName);
        }
        if (!returnTypesToRegister.isEmpty()) {
            reflectiveClassProducer.produce(ReflectiveClassBuildItem
                    .builder(returnTypesToRegister.stream().map(DotName::toString).toArray(String[]::new))
                    .constructors(false)
                    .build());
        }
    }

    @BuildStep
    public void findDeclarativeServices(CombinedIndexBuildItem indexBuildItem,
            BuildProducer<RequestChatModelBeanBuildItem> requestChatModelBeanProducer,
            BuildProducer<RequestModerationModelBeanBuildItem> requestModerationModelBeanProducer,
            BuildProducer<DeclarativeAiServiceBuildItem> declarativeAiServiceProducer,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClassProducer) {
        IndexView index = indexBuildItem.getIndex();

        Set<String> chatModelNames = new HashSet<>();
        boolean needModerationModelBean = false;
        for (AnnotationInstance instance : index.getAnnotations(Langchain4jDotNames.REGISTER_AI_SERVICES)) {
            if (instance.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue; // should never happen
            }
            ClassInfo declarativeAiServiceClassInfo = instance.target().asClass();

            DotName chatLanguageModelSupplierClassDotName = null;
            AnnotationValue chatLanguageModelSupplierValue = instance.value("chatLanguageModelSupplier");
            if (chatLanguageModelSupplierValue != null) {
                chatLanguageModelSupplierClassDotName = chatLanguageModelSupplierValue.asClass().name();
                if (chatLanguageModelSupplierClassDotName.equals(Langchain4jDotNames.BEAN_CHAT_MODEL_SUPPLIER)) { // this is the case where the default was set, so we just ignore it
                    chatLanguageModelSupplierClassDotName = null;
                } else {
                    validateSupplierAndRegisterForReflection(chatLanguageModelSupplierClassDotName, index,
                            reflectiveClassProducer);
                }
            }

            String modeName = NamedModelUtil.DEFAULT_NAME;
            if (chatLanguageModelSupplierClassDotName == null) {
                AnnotationValue modelNameValue = instance.value("modelName");
                if (modelNameValue != null) {
                    String modelNameValueStr = modelNameValue.asString();
                    if ((modelNameValueStr != null) && !modelNameValueStr.isEmpty()) {
                        modeName = modelNameValueStr;
                    }
                }
                chatModelNames.add(modeName);
            }

            List<DotName> toolDotNames = Collections.emptyList();
            AnnotationValue toolsInstance = instance.value("tools");
            if (toolsInstance != null) {
                toolDotNames = Arrays.stream(toolsInstance.asClassArray()).map(Type::name)
                        .collect(Collectors.toList());
            }

            // the default value depends on whether tools exists or not - if they do, then we require a ChatMemoryProvider bean
            DotName chatMemoryProviderSupplierClassDotName = Langchain4jDotNames.BEAN_CHAT_MEMORY_PROVIDER_SUPPLIER;
            AnnotationValue chatMemoryProviderSupplierValue = instance.value("chatMemoryProviderSupplier");
            if (chatMemoryProviderSupplierValue != null) {
                chatMemoryProviderSupplierClassDotName = chatMemoryProviderSupplierValue.asClass().name();
                if (!chatMemoryProviderSupplierClassDotName
                        .equals(Langchain4jDotNames.BEAN_CHAT_MEMORY_PROVIDER_SUPPLIER)) {
                    validateSupplierAndRegisterForReflection(chatMemoryProviderSupplierClassDotName, index,
                            reflectiveClassProducer);
                }
            }

            DotName retrieverClassDotName = null;
            AnnotationValue retrieverValue = instance.value("retriever");
            if (retrieverValue != null) {
                retrieverClassDotName = retrieverValue.asClass().name();
                if (NO_RETRIEVER.equals(retrieverClassDotName)) {
                    retrieverClassDotName = null;
                }
            }

            DotName auditServiceSupplierClassName = Langchain4jDotNames.BEAN_IF_EXISTS_AUDIT_SERVICE_SUPPLIER;
            AnnotationValue auditServiceSupplierValue = instance.value("auditServiceSupplier");
            if (auditServiceSupplierValue != null) {
                auditServiceSupplierClassName = auditServiceSupplierValue.asClass().name();
                validateSupplierAndRegisterForReflection(auditServiceSupplierClassName, index, reflectiveClassProducer);
            }

            DotName moderationModelSupplierClassName = null;
            AnnotationValue moderationModelSupplierValue = instance.value("moderationModelSupplier");
            if (moderationModelSupplierValue != null) {
                moderationModelSupplierClassName = moderationModelSupplierValue.asClass().name();
                if (Langchain4jDotNames.NO_MODERATION_MODEL_SUPPLIER.equals(moderationModelSupplierClassName)) {
                    moderationModelSupplierClassName = null;
                } else if (Langchain4jDotNames.BEAN_MODERATION_MODEL_SUPPLIER.equals(moderationModelSupplierClassName)) {
                    needModerationModelBean = true;
                } else {
                    validateSupplierAndRegisterForReflection(moderationModelSupplierClassName, index, reflectiveClassProducer);
                }
            }

            BuiltinScope declaredScope = BuiltinScope.from(declarativeAiServiceClassInfo);
            ScopeInfo cdiScope = declaredScope != null ? declaredScope.getInfo() : BuiltinScope.REQUEST.getInfo();

            declarativeAiServiceProducer.produce(
                    new DeclarativeAiServiceBuildItem(
                            declarativeAiServiceClassInfo,
                            chatLanguageModelSupplierClassDotName,
                            toolDotNames,
                            chatMemoryProviderSupplierClassDotName,
                            retrieverClassDotName,
                            auditServiceSupplierClassName,
                            moderationModelSupplierClassName,
                            cdiScope,
                            modeName));
        }

        for (String chatModelName : chatModelNames) {
            requestChatModelBeanProducer.produce(new RequestChatModelBeanBuildItem(chatModelName));
        }
        if (needModerationModelBean) {
            requestModerationModelBeanProducer.produce(new RequestModerationModelBeanBuildItem());
        }
    }

    private void validateSupplierAndRegisterForReflection(DotName supplierDotName, IndexView index,
            BuildProducer<ReflectiveClassBuildItem> producer) {
        ClassInfo classInfo = index.getClassByName(supplierDotName);
        if (classInfo == null) {
            log.warn("'" + supplierDotName.toString() + "' cannot be indexed"); // TODO: maybe this should be an error
            return;
        }

        if (!classInfo.hasNoArgsConstructor()) {
            throw new IllegalConfigurationException(
                    "Class '" + supplierDotName.toString() + "' which must contain a no-args constructor.");
        }

        producer.produce(ReflectiveClassBuildItem.builder(supplierDotName.toString()).constructors(true).build());
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    public void handleDeclarativeServices(AiServicesRecorder recorder,
            List<DeclarativeAiServiceBuildItem> declarativeAiServiceItems,
            List<SelectedChatModelProviderBuildItem> selectedChatModelProvider,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanProducer,
            BuildProducer<UnremovableBeanBuildItem> unremoveableProducer) {

        boolean needsChatModelBean = false;
        boolean needsChatMemoryProviderBean = false;
        boolean needsRetrieverBean = false;
        boolean needsAuditServiceBean = false;
        boolean needsModerationModelBean = false;
        Set<DotName> allToolNames = new HashSet<>();

        for (DeclarativeAiServiceBuildItem bi : declarativeAiServiceItems) {
            ClassInfo declarativeAiServiceClassInfo = bi.getServiceClassInfo();
            String serviceClassName = declarativeAiServiceClassInfo.name().toString();

            String chatLanguageModelSupplierClassName = (bi.getLanguageModelSupplierClassDotName() != null
                    ? bi.getLanguageModelSupplierClassDotName().toString()
                    : null);

            List<String> toolClassNames = bi.getToolDotNames().stream().map(DotName::toString).collect(Collectors.toList());

            String chatMemoryProviderSupplierClassName = bi.getChatMemoryProviderSupplierClassDotName() != null
                    ? bi.getChatMemoryProviderSupplierClassDotName().toString()
                    : null;

            String retrieverClassName = bi.getRetrieverClassDotName() != null
                    ? bi.getRetrieverClassDotName().toString()
                    : null;

            String auditServiceClassSupplierName = bi.getAuditServiceClassSupplierDotName() != null
                    ? bi.getAuditServiceClassSupplierDotName().toString()
                    : null;

            String moderationModelSupplierClassName = (bi.getModerationModelSupplierDotName() != null
                    ? bi.getModerationModelSupplierDotName().toString()
                    : null);

            String chatModelName = bi.getChatModelName();
            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(QuarkusAiServiceContext.class)
                    .createWith(recorder.createDeclarativeAiService(
                            new DeclarativeAiServiceCreateInfo(serviceClassName, chatLanguageModelSupplierClassName,
                                    toolClassNames, chatMemoryProviderSupplierClassName,
                                    retrieverClassName,
                                    auditServiceClassSupplierName,
                                    moderationModelSupplierClassName, chatModelName)))
                    .setRuntimeInit()
                    .addQualifier()
                    .annotation(Langchain4jDotNames.QUARKUS_AI_SERVICE_CONTEXT_QUALIFIER).addValue("value", serviceClassName)
                    .done()
                    .scope(Dependent.class);
            if ((chatLanguageModelSupplierClassName == null) && !selectedChatModelProvider.isEmpty()) {
                if (NamedModelUtil.isDefault(chatModelName)) {
                    configurator.addInjectionPoint(CHAT_MODEL_CLASS_TYPE);
                } else {
                    configurator.addInjectionPoint(CHAT_MODEL_CLASS_TYPE,
                            AnnotationInstance.builder(ModelName.class).add("value", chatModelName).build());

                }
                needsChatModelBean = true;
            }

            if (!toolClassNames.isEmpty()) {
                for (String toolClassName : toolClassNames) {
                    DotName dotName = DotName.createSimple(toolClassName);
                    configurator.addInjectionPoint(ClassType.create(dotName));
                    allToolNames.add(dotName);
                }
            }

            if (Langchain4jDotNames.BEAN_CHAT_MEMORY_PROVIDER_SUPPLIER.toString().equals(chatMemoryProviderSupplierClassName)) {
                configurator.addInjectionPoint(ClassType.create(Langchain4jDotNames.CHAT_MEMORY_PROVIDER));
                needsChatMemoryProviderBean = true;
            }

            if (retrieverClassName != null) {
                configurator.addInjectionPoint(ClassType.create(retrieverClassName));
                needsRetrieverBean = true;
            }

            if (Langchain4jDotNames.BEAN_IF_EXISTS_AUDIT_SERVICE_SUPPLIER.toString().equals(auditServiceClassSupplierName)) {
                configurator.addInjectionPoint(ParameterizedType.create(CDI_INSTANCE,
                        new Type[] { ClassType.create(Langchain4jDotNames.AUDIT_SERVICE) }, null));
                needsAuditServiceBean = true;
            }

            if (Langchain4jDotNames.BEAN_MODERATION_MODEL_SUPPLIER.toString().equals(moderationModelSupplierClassName)) {
                configurator.addInjectionPoint(ClassType.create(Langchain4jDotNames.MODERATION_MODEL));
                needsModerationModelBean = true;
            }

            syntheticBeanProducer.produce(configurator.done());
        }

        if (needsChatModelBean) {
            unremoveableProducer.produce(UnremovableBeanBuildItem.beanTypes(Langchain4jDotNames.CHAT_MODEL));
        }
        if (needsChatMemoryProviderBean) {
            unremoveableProducer.produce(UnremovableBeanBuildItem.beanTypes(Langchain4jDotNames.CHAT_MEMORY_PROVIDER));
        }
        if (needsRetrieverBean) {
            unremoveableProducer.produce(UnremovableBeanBuildItem.beanTypes(Langchain4jDotNames.RETRIEVER));
        }
        if (needsAuditServiceBean) {
            unremoveableProducer.produce(UnremovableBeanBuildItem.beanTypes(Langchain4jDotNames.AUDIT_SERVICE));
        }
        if (needsModerationModelBean) {
            unremoveableProducer.produce(UnremovableBeanBuildItem.beanTypes(Langchain4jDotNames.MODERATION_MODEL));
        }
        if (!allToolNames.isEmpty()) {
            unremoveableProducer.produce(UnremovableBeanBuildItem.beanTypes(allToolNames));
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    public void handleAiServices(AiServicesRecorder recorder,
            CombinedIndexBuildItem indexBuildItem,
            List<DeclarativeAiServiceBuildItem> declarativeAiServiceItems,
            BuildProducer<GeneratedClassBuildItem> generatedClassProducer,
            BuildProducer<GeneratedBeanBuildItem> generatedBeanProducer,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClassProducer,
            BuildProducer<AiServicesMethodBuildItem> aiServicesMethodProducer,
            BuildProducer<AdditionalBeanBuildItem> additionalBeanProducer,
            Optional<MetricsCapabilityBuildItem> metricsCapability,
            Capabilities capabilities) {

        IndexView index = indexBuildItem.getIndex();

        List<AiServicesUseAnalyzer.Result.Entry> aiServicesAnalysisResults = new ArrayList<>();
        for (ClassInfo classInfo : index.getKnownUsers(Langchain4jDotNames.AI_SERVICES)) {
            String className = classInfo.name().toString();
            if (className.startsWith("io.quarkiverse.langchain4j") || className.startsWith("dev.langchain4j")) { // TODO: this can be made smarter if needed
                continue;
            }
            try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(
                    className.replace('.', '/') + ".class")) {
                if (is == null) {
                    return;
                }
                var cn = new ClassNode(Gizmo.ASM_API_VERSION);
                var cr = new ClassReader(is);
                cr.accept(cn, 0);
                for (MethodNode method : cn.methods) {
                    aiServicesAnalysisResults.addAll(AiServicesUseAnalyzer.analyze(cn, method).entries);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Reading bytecode of class '" + className + "' failed", e);
            } catch (AnalyzerException e) {
                log.debug("Unable to analyze bytecode of class '" + className + "'", e);
            }
        }
        Map<String, Boolean> nameToUsed = aiServicesAnalysisResults.stream()
                .collect(Collectors.toMap(e -> e.createdClassName, e -> e.chatMemoryProviderUsed, (u1, u2) -> u1 || u2));
        for (var entry : nameToUsed.entrySet()) {
            String className = entry.getKey();
            ClassInfo classInfo = index.getClassByName(className);
            if (classInfo == null) {
                continue;
            }
            if (!classInfo.annotations(Langchain4jDotNames.MEMORY_ID).isEmpty() && !entry.getValue()) {
                log.warn("Class '" + className
                        + "' is used in AiServices and while it leverages @MemoryId, a ChatMemoryProvider has not been configured. This will likely result in an exception being thrown when the service is used.");
            }
        }

        Set<String> detectedForCreate = new HashSet<>(nameToUsed.keySet());
        addCreatedAware(index, detectedForCreate);
        addIfacesWithMessageAnns(index, detectedForCreate);
        Set<String> registeredAiServiceClassNames = declarativeAiServiceItems.stream()
                .map(bi -> bi.getServiceClassInfo().name().toString()).collect(
                        Collectors.toUnmodifiableSet());
        detectedForCreate.addAll(registeredAiServiceClassNames);

        Set<ClassInfo> ifacesForCreate = new HashSet<>();
        for (String className : detectedForCreate) {
            ClassInfo classInfo = index.getClassByName(className);
            if (classInfo == null) {
                log.warn("'" + className
                        + "' used for creating an AiService was not found in the Quarkus index. Attempting to create "
                        + "an AiService using this class will fail");
                continue;
            }
            if (!classInfo.isInterface()) {
                log.warn("'" + className
                        + "' used for creating an AiService is not an interface. Attempting to create an AiService "
                        + "using this class will fail");
            }

            ifacesForCreate.add(classInfo);
        }

        var addMicrometerMetrics = metricsCapability.isPresent()
                && metricsCapability.get().metricsSupported(MetricsFactory.MICROMETER);
        if (addMicrometerMetrics) {
            additionalBeanProducer.produce(AdditionalBeanBuildItem.builder().addBeanClass(MetricsTimedWrapper.class).build());
            additionalBeanProducer.produce(AdditionalBeanBuildItem.builder().addBeanClass(MetricsCountedWrapper.class).build());
        }

        var addOpenTelemetrySpan = capabilities.isPresent(Capability.OPENTELEMETRY_TRACER);
        if (addOpenTelemetrySpan) {
            additionalBeanProducer.produce(AdditionalBeanBuildItem.builder().addBeanClass(SpanWrapper.class).build());
        }

        Map<String, AiServiceClassCreateInfo> perClassMetadata = new HashMap<>();
        if (!ifacesForCreate.isEmpty()) {
            ClassOutput generatedClassOutput = new GeneratedClassGizmoAdaptor(generatedClassProducer, true);
            ClassOutput generatedBeanOutput = new GeneratedBeanGizmoAdaptor(generatedBeanProducer);
            for (ClassInfo iface : ifacesForCreate) {
                Set<MethodInfo> allMethods = new HashSet<>(iface.methods());
                JandexUtil.getAllSuperinterfaces(iface, index).forEach(ci -> allMethods.addAll(ci.methods()));

                List<MethodInfo> methodsToImplement = new ArrayList<>();
                Map<String, AiServiceMethodCreateInfo> perMethodMetadata = new HashMap<>();
                for (MethodInfo method : allMethods) {
                    short modifiers = method.flags();
                    if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers) || JandexUtil.isDefault(
                            modifiers)) {
                        continue;
                    }
                    methodsToImplement.add(method);
                }

                String ifaceName = iface.name().toString();
                String implClassName = ifaceName + "$$QuarkusImpl";
                boolean isRegisteredService = registeredAiServiceClassNames.contains(ifaceName);

                ClassCreator.Builder classCreatorBuilder = ClassCreator.builder()
                        .classOutput(isRegisteredService ? generatedBeanOutput : generatedClassOutput)
                        .className(implClassName)
                        .interfaces(ifaceName, ChatMemoryRemovable.class.getName());
                if (isRegisteredService) {
                    classCreatorBuilder.interfaces(AutoCloseable.class);
                }
                try (ClassCreator classCreator = classCreatorBuilder.build()) {
                    if (isRegisteredService) {
                        // we need to make this a bean, so we need to add the proper scope annotation
                        ScopeInfo scopeInfo = declarativeAiServiceItems.stream()
                                .filter(bi -> bi.getServiceClassInfo().equals(iface))
                                .findFirst().orElseThrow(() -> new IllegalStateException(
                                        "Unable to determine the CDI scope of " + iface))
                                .getCdiScope();
                        classCreator.addAnnotation(scopeInfo.getDotName().toString());
                    }

                    FieldDescriptor contextField = classCreator.getFieldCreator("context", QuarkusAiServiceContext.class)
                            .setModifiers(Modifier.PRIVATE | Modifier.FINAL)
                            .getFieldDescriptor();

                    for (MethodInfo methodInfo : methodsToImplement) {
                        // The implementation essentially gets the context and delegates to
                        // MethodImplementationSupport#implement

                        String methodId = createMethodId(methodInfo);
                        perMethodMetadata.put(methodId,
                                gatherMethodMetadata(methodInfo, addMicrometerMetrics, addOpenTelemetrySpan));
                        {
                            MethodCreator ctor = classCreator.getMethodCreator(MethodDescriptor.INIT, "V",
                                    QuarkusAiServiceContext.class);
                            ctor.setModifiers(Modifier.PUBLIC);
                            ctor.addAnnotation(Inject.class);
                            ctor.getParameterAnnotations(0)
                                    .addAnnotation(Langchain4jDotNames.QUARKUS_AI_SERVICE_CONTEXT_QUALIFIER.toString())
                                    .add("value", ifaceName);
                            ctor.invokeSpecialMethod(OBJECT_CONSTRUCTOR, ctor.getThis());
                            ctor.writeInstanceField(contextField, ctor.getThis(),
                                    ctor.getMethodParam(0));
                            ctor.returnValue(null);
                        }

                        {
                            MethodCreator noArgsCtor = classCreator.getMethodCreator(MethodDescriptor.INIT, "V");
                            noArgsCtor.setModifiers(Modifier.PUBLIC);
                            noArgsCtor.invokeSpecialMethod(OBJECT_CONSTRUCTOR, noArgsCtor.getThis());
                            noArgsCtor.writeInstanceField(contextField, noArgsCtor.getThis(), noArgsCtor.loadNull());
                            noArgsCtor.returnValue(null);
                        }

                        { // actual method we need to implement
                            MethodCreator mc = classCreator.getMethodCreator(MethodDescriptor.of(methodInfo));

                            // copy annotations
                            for (AnnotationInstance annotationInstance : methodInfo.declaredAnnotations()) {
                                // TODO: we need to review this
                                if (annotationInstance.name().toString()
                                        .startsWith("org.eclipse.microprofile.faulttolerance")
                                        || annotationInstance.name().toString()
                                                .startsWith("io.smallrye.faulttolerance.api")) {
                                    mc.addAnnotation(annotationInstance);
                                }
                            }

                            ResultHandle contextHandle = mc.readInstanceField(contextField, mc.getThis());
                            ResultHandle methodCreateInfoHandle = mc.invokeStaticMethod(RECORDER_METHOD_CREATE_INFO,
                                    mc.load(ifaceName),
                                    mc.load(methodId));
                            ResultHandle paramsHandle = mc.newArray(Object.class, methodInfo.parametersCount());
                            for (int i = 0; i < methodInfo.parametersCount(); i++) {
                                mc.writeArrayValue(paramsHandle, i, mc.getMethodParam(i));
                            }

                            ResultHandle supportHandle = getFromCDI(mc, AiServiceMethodImplementationSupport.class.getName());
                            ResultHandle inputHandle = mc.newInstance(
                                    MethodDescriptor.ofConstructor(AiServiceMethodImplementationSupport.Input.class,
                                            QuarkusAiServiceContext.class, AiServiceMethodCreateInfo.class,
                                            Object[].class),
                                    contextHandle, methodCreateInfoHandle, paramsHandle);

                            ResultHandle resultHandle = mc.invokeVirtualMethod(SUPPORT_IMPLEMENT, supportHandle, inputHandle);
                            mc.returnValue(resultHandle);

                            aiServicesMethodProducer.produce(new AiServicesMethodBuildItem(methodInfo));
                        }
                    }

                    if (isRegisteredService) {
                        MethodCreator mc = classCreator.getMethodCreator(
                                MethodDescriptor.ofMethod(implClassName, "close", void.class));
                        mc.addAnnotation(PreDestroy.class);
                        ResultHandle contextHandle = mc.readInstanceField(contextField, mc.getThis());
                        mc.invokeVirtualMethod(QUARKUS_AI_SERVICES_CONTEXT_CLOSE, contextHandle);
                        mc.returnVoid();
                    }

                    {
                        MethodCreator mc = classCreator.getMethodCreator(
                                MethodDescriptor.ofMethod(implClassName, "remove", void.class, Object[].class));
                        ResultHandle contextHandle = mc.readInstanceField(contextField, mc.getThis());
                        mc.invokeVirtualMethod(QUARKUS_AI_SERVICES_CONTEXT_REMOVE_CHAT_MEMORY_IDS, contextHandle,
                                mc.getMethodParam(0));
                        mc.returnVoid();
                    }

                }
                perClassMetadata.put(ifaceName, new AiServiceClassCreateInfo(perMethodMetadata, implClassName));
                // make the constructor accessible reflectively since that is how we create the instance
                reflectiveClassProducer.produce(ReflectiveClassBuildItem.builder(implClassName).build());
            }

        }

        recorder.setMetadata(perClassMetadata);
    }

    private ResultHandle getFromCDI(MethodCreator mc, String className) {
        ResultHandle containerHandle = mc
                .invokeStaticMethod(MethodDescriptor.ofMethod(Arc.class, "container", ArcContainer.class));
        ResultHandle instanceHandle = mc.invokeInterfaceMethod(
                MethodDescriptor.ofMethod(ArcContainer.class, "instance", InstanceHandle.class, Class.class,
                        Annotation[].class),
                containerHandle, mc.loadClassFromTCCL(className),
                mc.newArray(Annotation.class, 0));
        return mc.invokeInterfaceMethod(MethodDescriptor.ofMethod(InstanceHandle.class, "get", Object.class), instanceHandle);
    }

    private String createMethodId(MethodInfo methodInfo) {
        return methodInfo.name() + '('
                + Arrays.toString(methodInfo.parameters().stream().map(mp -> mp.type().name().toString()).toArray()) + ')';
    }

    private void addIfacesWithMessageAnns(IndexView index, Set<String> detectedForCreate) {
        List<DotName> annotations = List.of(Langchain4jDotNames.SYSTEM_MESSAGE, Langchain4jDotNames.USER_MESSAGE,
                Langchain4jDotNames.MODERATE);
        for (DotName annotation : annotations) {
            Collection<AnnotationInstance> instances = index.getAnnotations(annotation);
            for (AnnotationInstance instance : instances) {
                if (instance.target().kind() != AnnotationTarget.Kind.METHOD) {
                    continue;
                }
                ClassInfo declaringClass = instance.target().asMethod().declaringClass();
                if (declaringClass.isInterface()) {
                    detectedForCreate.add(declaringClass.name().toString());
                }
            }
        }
    }

    private static void addCreatedAware(IndexView index, Set<String> detectedForCreate) {
        Collection<AnnotationInstance> instances = index.getAnnotations(Langchain4jDotNames.CREATED_AWARE);
        for (var instance : instances) {
            if (instance.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            detectedForCreate.add(instance.target().asClass().name().toString());
        }
    }

    private AiServiceMethodCreateInfo gatherMethodMetadata(MethodInfo method, boolean addMicrometerMetrics,
            boolean addOpenTelemetrySpans) {
        if (method.returnType().kind() == Type.Kind.VOID) {
            throw illegalConfiguration("Return type of method '%s' cannot be void", method);
        }

        boolean requiresModeration = method.hasAnnotation(Langchain4jDotNames.MODERATE);

        List<MethodParameterInfo> params = method.parameters();

        List<TemplateParameterInfo> templateParams = gatherTemplateParamInfo(params);
        Optional<AiServiceMethodCreateInfo.TemplateInfo> systemMessageInfo = gatherSystemMessageInfo(method, templateParams);
        Class<?> returnType = JandexUtil.load(method.returnType(), Thread.currentThread().getContextClassLoader());
        AiServiceMethodCreateInfo.UserMessageInfo userMessageInfo = gatherUserMessageInfo(method, templateParams,
                returnType);
        Optional<Integer> memoryIdParamPosition = gatherMemoryIdParamName(method);
        Optional<AiServiceMethodCreateInfo.MetricsTimedInfo> metricsTimedInfo = gatherMetricsTimedInfo(method,
                addMicrometerMetrics);
        Optional<AiServiceMethodCreateInfo.MetricsCountedInfo> metricsCountedInfo = gatherMetricsCountedInfo(method,
                addMicrometerMetrics);
        Optional<AiServiceMethodCreateInfo.SpanInfo> spanInfo = gatherSpanInfo(method, addOpenTelemetrySpans);

        return new AiServiceMethodCreateInfo(method.declaringClass().name().toString(), method.name(), systemMessageInfo,
                userMessageInfo, memoryIdParamPosition, requiresModeration,
                returnType, metricsTimedInfo, metricsCountedInfo, spanInfo);
    }

    private List<TemplateParameterInfo> gatherTemplateParamInfo(List<MethodParameterInfo> params) {
        if (params.isEmpty()) {
            return Collections.emptyList();
        }

        List<TemplateParameterInfo> templateParams = new ArrayList<>();
        for (MethodParameterInfo param : params) {
            if (effectiveParamAnnotations(param).isEmpty()) { // if a parameter has no annotations it is considered a template variable
                templateParams.add(new TemplateParameterInfo(param.position(), param.name()));
            } else {
                AnnotationInstance vInstance = param.annotation(V);
                if (vInstance != null) {
                    AnnotationValue value = vInstance.value();
                    if (value != null) {
                        templateParams.add(new TemplateParameterInfo(param.position(), value.asString()));
                    }
                }
            }
        }

        if ((templateParams.size() == 1) && (params.size() == 1)) {
            // the special 'it' param is supported when the method only has one parameter
            templateParams.add(new TemplateParameterInfo(0, "it"));
        }

        return templateParams;
    }

    private List<AnnotationInstance> effectiveParamAnnotations(MethodParameterInfo param) {
        return param.annotations().stream().filter(ai -> {
            String name = ai.name().toString();
            if (name.startsWith("kotlin") || name.startsWith("jakarta.validation.constraints")) {
                return false;
            }
            if (name.endsWith("NotNull")) {
                return false;
            }
            if (name.startsWith("io.opentelemetry")) {
                return false;
            }
            return true;
        }).collect(Collectors.toList());
    }

    private Optional<AiServiceMethodCreateInfo.TemplateInfo> gatherSystemMessageInfo(MethodInfo method,
            List<TemplateParameterInfo> templateParams) {
        AnnotationInstance instance = method.annotation(Langchain4jDotNames.SYSTEM_MESSAGE);
        if (instance != null) {
            String systemMessageTemplate = "";
            AnnotationValue delimiterValue = instance.value("delimiter");
            String delimiter = delimiterValue != null ? delimiterValue.asString() : DEFAULT_DELIMITER;
            AnnotationValue value = instance.value();
            if (value != null) {
                systemMessageTemplate = String.join(delimiter, value.asStringArray());
            }
            if (systemMessageTemplate.isEmpty()) {
                throw illegalConfigurationForMethod("@SystemMessage's template parameter cannot be empty", method);
            }

            // TODO: we should probably add a lot more template validation here
            return Optional.of(
                    new AiServiceMethodCreateInfo.TemplateInfo(
                            systemMessageTemplate,
                            TemplateParameterInfo.toNameToArgsPositionMap(templateParams)));
        }
        return Optional.empty();
    }

    private Optional<Integer> gatherMemoryIdParamName(MethodInfo method) {
        return method.annotations(Langchain4jDotNames.MEMORY_ID).stream().filter(IS_METHOD_PARAMETER_ANNOTATION)
                .map(METHOD_PARAMETER_POSITION_FUNCTION)
                .findFirst();
    }

    private AiServiceMethodCreateInfo.UserMessageInfo gatherUserMessageInfo(MethodInfo method,
            List<TemplateParameterInfo> templateParams, Class<?> returnType) {
        String outputFormatInstructions = outputFormatInstructions(returnType);

        Optional<Integer> userNameParamName = method.annotations(Langchain4jDotNames.USER_NAME).stream().filter(
                IS_METHOD_PARAMETER_ANNOTATION).map(METHOD_PARAMETER_POSITION_FUNCTION).findFirst();

        AnnotationInstance userMessageInstance = method.declaredAnnotation(Langchain4jDotNames.USER_MESSAGE);
        if (userMessageInstance != null) {
            AnnotationValue delimiterValue = userMessageInstance.value("delimiter");
            String delimiter = delimiterValue != null ? delimiterValue.asString() : DEFAULT_DELIMITER;
            String userMessageTemplate = String.join(delimiter, userMessageInstance.value().asStringArray());

            if (userMessageTemplate.contains("{{it}}")) {
                if (method.parametersCount() != 1) {
                    throw illegalConfigurationForMethod(
                            "Error: The {{it}} placeholder is present but the method does not have exactly one parameter. " +
                                    "Please ensure that methods using the {{it}} placeholder have exactly one parameter",
                            method);
                }
            }

            // TODO: we should probably add a lot more template validation here
            return AiServiceMethodCreateInfo.UserMessageInfo.fromTemplate(
                    new AiServiceMethodCreateInfo.TemplateInfo(userMessageTemplate,
                            TemplateParameterInfo.toNameToArgsPositionMap(templateParams)),
                    userNameParamName, outputFormatInstructions);
        } else {
            Optional<AnnotationInstance> userMessageOnMethodParam = method.annotations(Langchain4jDotNames.USER_MESSAGE)
                    .stream()
                    .filter(IS_METHOD_PARAMETER_ANNOTATION).findFirst();
            if (userMessageOnMethodParam.isPresent()) {
                return AiServiceMethodCreateInfo.UserMessageInfo.fromMethodParam(
                        userMessageOnMethodParam.get().target().asMethodParameter().position(),
                        userNameParamName, outputFormatInstructions);
            } else {
                if (method.parametersCount() == 0) {
                    throw illegalConfigurationForMethod("Method should have at least one argument", method);
                }
                if (method.parametersCount() == 1) {
                    return AiServiceMethodCreateInfo.UserMessageInfo.fromMethodParam(0, userNameParamName,
                            outputFormatInstructions);
                }

                throw illegalConfigurationForMethod(
                        "For methods with multiple parameters, each parameter must be annotated with @V (or match an template parameter by name), @UserMessage, @UserName or @MemoryId",
                        method);
            }
        }
    }

    private Optional<AiServiceMethodCreateInfo.MetricsTimedInfo> gatherMetricsTimedInfo(MethodInfo method,
            boolean addMicrometerMetrics) {
        if (!addMicrometerMetrics) {
            return Optional.empty();
        }

        String name = METRICS_DEFAULT_NAME;
        List<String> tags = defaultMetricsTags(method);

        AnnotationInstance timedInstance = method.annotation(MICROMETER_TIMED);
        if (timedInstance == null) {
            timedInstance = method.declaringClass().declaredAnnotation(MICROMETER_TIMED);
        }

        if (timedInstance == null) {
            // we default to having all AiServices being timed
            return Optional.of(new AiServiceMethodCreateInfo.MetricsTimedInfo.Builder(name)
                    .setExtraTags(tags.toArray(EMPTY_STRING_ARRAY)).build());
        }

        AnnotationValue nameValue = timedInstance.value();
        if (nameValue != null) {
            String nameStr = nameValue.asString();
            if (nameStr != null && !nameStr.isEmpty()) {
                name = nameStr;
            }
        }

        var builder = new AiServiceMethodCreateInfo.MetricsTimedInfo.Builder(name);

        AnnotationValue extraTagsValue = timedInstance.value("extraTags");
        if (extraTagsValue != null) {
            tags.addAll(Arrays.asList(extraTagsValue.asStringArray()));
        }
        builder.setExtraTags(tags.toArray(EMPTY_STRING_ARRAY));

        AnnotationValue longTaskValue = timedInstance.value("longTask");
        if (longTaskValue != null) {
            builder.setLongTask(longTaskValue.asBoolean());
        }

        AnnotationValue percentilesValue = timedInstance.value("percentiles");
        if (percentilesValue != null) {
            builder.setPercentiles(percentilesValue.asDoubleArray());
        }

        AnnotationValue histogramValue = timedInstance.value("histogram");
        if (histogramValue != null) {
            builder.setHistogram(histogramValue.asBoolean());
        }

        AnnotationValue descriptionValue = timedInstance.value("description");
        if (descriptionValue != null) {
            builder.setDescription(descriptionValue.asString());
        }

        return Optional.of(builder.build());
    }

    private Optional<AiServiceMethodCreateInfo.MetricsCountedInfo> gatherMetricsCountedInfo(MethodInfo method,
            boolean addMicrometerMetrics) {
        if (!addMicrometerMetrics) {
            return Optional.empty();
        }

        String name = METRICS_DEFAULT_NAME;
        List<String> tags = defaultMetricsTags(method);

        AnnotationInstance timedInstance = method.annotation(MICROMETER_COUNTED);
        if (timedInstance == null) {
            timedInstance = method.declaringClass().declaredAnnotation(MICROMETER_COUNTED);
        }

        if (timedInstance == null) {
            // we default to having all AiServices being timed
            return Optional.of(new AiServiceMethodCreateInfo.MetricsCountedInfo.Builder(name)
                    .setExtraTags(tags.toArray(EMPTY_STRING_ARRAY)).build());
        }

        AnnotationValue nameValue = timedInstance.value();
        if (nameValue != null) {
            String nameStr = nameValue.asString();
            if (nameStr != null && !nameStr.isEmpty()) {
                name = nameStr;
            }
        }

        var builder = new AiServiceMethodCreateInfo.MetricsCountedInfo.Builder(name);

        AnnotationValue extraTagsValue = timedInstance.value("extraTags");
        if (extraTagsValue != null) {
            tags.addAll(Arrays.asList(extraTagsValue.asStringArray()));
        }
        builder.setExtraTags(tags.toArray(EMPTY_STRING_ARRAY));

        AnnotationValue recordFailuresOnlyValue = timedInstance.value("recordFailuresOnly");
        if (recordFailuresOnlyValue != null) {
            builder.setRecordFailuresOnly(recordFailuresOnlyValue.asBoolean());
        }

        AnnotationValue descriptionValue = timedInstance.value("description");
        if (descriptionValue != null) {
            builder.setDescription(descriptionValue.asString());
        }

        return Optional.of(builder.build());
    }

    private List<String> defaultMetricsTags(MethodInfo method) {
        List<String> tags = new ArrayList<>(4);
        tags.add("aiservice");
        tags.add(method.declaringClass().name().withoutPackagePrefix());
        tags.add("method");
        tags.add(method.name());
        return tags;
    }

    private Optional<AiServiceMethodCreateInfo.SpanInfo> gatherSpanInfo(MethodInfo method,
            boolean addOpenTelemetrySpans) {
        if (!addOpenTelemetrySpans) {
            return Optional.empty();
        }

        String name = defaultAiServiceSpanName(method);

        // TODO: add more

        return Optional.of(new AiServiceMethodCreateInfo.SpanInfo(name));
    }

    private String defaultAiServiceSpanName(MethodInfo method) {
        return "langchain4j.aiservices." + method.declaringClass().name().withoutPackagePrefix() + "." + method.name();
    }

    private static class TemplateParameterInfo {
        private final int position;
        private final String name;

        public TemplateParameterInfo(int position, String name) {
            this.position = position;
            this.name = name;
        }

        public int getPosition() {
            return position;
        }

        public String getName() {
            return name;
        }

        static Map<String, Integer> toNameToArgsPositionMap(List<TemplateParameterInfo> list) {
            return list.stream().collect(Collectors.toMap(TemplateParameterInfo::getName, TemplateParameterInfo::getPosition));
        }
    }

    public static final class AiServicesMethodBuildItem extends MultiBuildItem {

        private final MethodInfo methodInfo;

        public AiServicesMethodBuildItem(MethodInfo methodInfo) {
            this.methodInfo = methodInfo;
        }

        public MethodInfo getMethodInfo() {
            return methodInfo;
        }
    }
}
