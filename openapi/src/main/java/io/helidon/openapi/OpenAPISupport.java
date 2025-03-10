/*
 * Copyright (c) 2020, 2023 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.openapi;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import io.helidon.common.http.Http;
import io.helidon.common.http.MediaType;
import io.helidon.config.Config;
import io.helidon.config.metadata.Configured;
import io.helidon.config.metadata.ConfiguredOption;
import io.helidon.media.common.MessageBodyReaderContext;
import io.helidon.media.common.MessageBodyWriterContext;
import io.helidon.media.jsonp.JsonpSupport;
import io.helidon.openapi.internal.OpenAPIConfigImpl;
import io.helidon.webserver.RequestHeaders;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import io.helidon.webserver.cors.CorsEnabledServiceHelper;
import io.helidon.webserver.cors.CrossOriginConfig;

import io.smallrye.openapi.api.OpenApiConfig;
import io.smallrye.openapi.api.OpenApiDocument;
import io.smallrye.openapi.api.models.OpenAPIImpl;
import io.smallrye.openapi.api.util.MergeUtil;
import io.smallrye.openapi.runtime.OpenApiProcessor;
import io.smallrye.openapi.runtime.OpenApiStaticFile;
import io.smallrye.openapi.runtime.io.Format;
import io.smallrye.openapi.runtime.scanner.AnnotationScannerExtension;
import io.smallrye.openapi.runtime.scanner.OpenApiAnnotationScanner;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.openapi.models.Extensible;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Reference;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.servers.ServerVariable;
import org.jboss.jandex.IndexView;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertySubstitute;

import static io.helidon.webserver.cors.CorsEnabledServiceHelper.CORS_CONFIG_KEY;

/**
 * Provides an endpoint and supporting logic for returning an OpenAPI document
 * that describes the endpoints handled by the server.
 * <p>
 * The server can use the {@link Builder} to set OpenAPI-related attributes. If
 * the server uses none of these builder methods and does not provide a static
 * {@code openapi} file, then the {@code /openapi} endpoint responds with a
 * nearly-empty OpenAPI document.
 *
 */
public abstract class OpenAPISupport implements Service {

    /**
     * Default path for serving the OpenAPI document.
     */
    public static final String DEFAULT_WEB_CONTEXT = "/openapi";

    /**
     * Default media type used in responses in absence of incoming Accept
     * header.
     */
    public static final MediaType DEFAULT_RESPONSE_MEDIA_TYPE = MediaType.APPLICATION_OPENAPI_YAML;

    /**
     * Some logic related to the possible format values as requested in the query
     * parameter {@value OPENAPI_ENDPOINT_FORMAT_QUERY_PARAMETER}.
     */
    enum QueryParameterRequestedFormat {
        JSON(MediaType.APPLICATION_JSON), YAML(MediaType.APPLICATION_OPENAPI_YAML);

        static QueryParameterRequestedFormat chooseFormat(String format) {
            return QueryParameterRequestedFormat.valueOf(format);
        }

        private final MediaType mt;

        QueryParameterRequestedFormat(MediaType mt) {
            this.mt = mt;
        }

        MediaType mediaType() {
            return mt;
        }
    }

    /**
     * URL query parameter for specifying the requested format when retrieving the OpenAPI document.
     */
    static final String OPENAPI_ENDPOINT_FORMAT_QUERY_PARAMETER = "format";

    private static final Logger LOGGER = Logger.getLogger(OpenAPISupport.class.getName());

    private static final String DEFAULT_STATIC_FILE_PATH_PREFIX = "META-INF/openapi.";
    private static final String OPENAPI_EXPLICIT_STATIC_FILE_LOG_MESSAGE_FORMAT = "Using specified OpenAPI static file %s";
    private static final String OPENAPI_DEFAULTED_STATIC_FILE_LOG_MESSAGE_FORMAT = "Using default OpenAPI static file %s";
    private static final String FEATURE_NAME = "OpenAPI";

    private static final JsonReaderFactory JSON_READER_FACTORY = Json.createReaderFactory(Collections.emptyMap());

    // As a static we keep a reference to the logger, thereby making sure any changes we make are persistent. (JUL holds
    // only weak references to loggers internally.)
    private static final Logger SNAKE_YAML_INTROSPECTOR_LOGGER =
            Logger.getLogger(PropertySubstitute.class.getPackage().getName());

    /**
     * The SnakeYAMLParserHelper is generated by a maven plug-in.
     */
    private static SnakeYAMLParserHelper<ExpandedTypeDescription> helper = null;

    private static final Lock HELPER_ACCESS = new ReentrantLock(true);

    private final String webContext;

    private OpenAPI model = null;
    private final ConcurrentMap<Format, String> cachedDocuments = new ConcurrentHashMap<>();
    private final Map<Class<?>, ExpandedTypeDescription> implsToTypes;
    private final CorsEnabledServiceHelper corsEnabledServiceHelper;

    /*
     * To handle the MP case, we must defer constructing the OpenAPI in-memory model until after the server has instantiated
     * the Application instances. By then the builder has already been used to build the OpenAPISupport object. So save the
     * following raw materials so we can construct the model at that later time.
     */
    private final OpenApiConfig openApiConfig;
    private final OpenApiStaticFile openApiStaticFile;
    private final Supplier<List<? extends IndexView>> indexViewsSupplier;

    private final Lock modelAccess = new ReentrantLock(true);

    private final OpenApiUi ui;

    private final MediaType[] preferredMediaTypeOrdering;
    private final MediaType[] mediaTypesSupportedByUi;

    /**
     * Creates a new instance of {@code OpenAPISupport}.
     *
     * @param builder the builder to use in constructing the instance
     */
    protected OpenAPISupport(Builder<?> builder) {
        adjustTypeDescriptions(helper().types());
        implsToTypes = buildImplsToTypes(helper());
        webContext = builder.webContext();
        corsEnabledServiceHelper = CorsEnabledServiceHelper.create(FEATURE_NAME, builder.crossOriginConfig);
        openApiConfig = builder.openAPIConfig();
        openApiStaticFile = builder.staticFile();
        indexViewsSupplier = builder.indexViewsSupplier();
        ui = prepareUi(builder);
        mediaTypesSupportedByUi = ui.supportedMediaTypes();
        preferredMediaTypeOrdering = preparePreferredMediaTypeOrdering(mediaTypesSupportedByUi);
    }

    @Override
    public void update(Routing.Rules rules) {
        configureEndpoint(rules);
    }

    /**
     * Sets up the OpenAPI endpoint by adding routing to the specified rules
     * set.
     *
     * @param rules routing rules to be augmented with OpenAPI endpoint
     */
    public void configureEndpoint(Routing.Rules rules) {

        rules.get(this::registerJsonpSupport)
                .any(webContext, corsEnabledServiceHelper.processor())
                .get(webContext, this::prepareResponse);
        ui.update(rules);
    }

    /**
     *
     * @return the web context setting for this service
     */
    public String webContext() {
        return webContext;
    }

    /**
     * Triggers preparation of the model from external code.
     */
    protected void prepareModel() {
        model();
    }

    private OpenApiUi prepareUi(Builder<?> builder) {
        return builder.uiBuilder.build(this::prepareDocument, webContext);
    }

    private static MediaType[] preparePreferredMediaTypeOrdering(MediaType[] uiTypesSupported) {
        int nonTextLength = OpenAPIMediaType.NON_TEXT_PREFERRED_ORDERING.length;

        MediaType[] result = Arrays.copyOf(OpenAPIMediaType.NON_TEXT_PREFERRED_ORDERING,
                                           nonTextLength + uiTypesSupported.length);
        System.arraycopy(uiTypesSupported, 0, result, nonTextLength, uiTypesSupported.length);
        return result;
    }

    private OpenAPI model() {
        return access(modelAccess, () -> {
            if (model == null) {
                model = prepareModel(openApiConfig, openApiStaticFile, indexViewsSupplier.get());
            }
            return model;
        });
    }

    private void registerJsonpSupport(ServerRequest req, ServerResponse res) {
        MessageBodyReaderContext readerContext = req.content().readerContext();
        MessageBodyWriterContext writerContext = res.writerContext();
        JsonpSupport.create().register(readerContext, writerContext);
        req.next();
    }

    static SnakeYAMLParserHelper<ExpandedTypeDescription> helper() {
        return access(HELPER_ACCESS, () -> {
            if (helper == null) {
                Config config = Config.create();
                boolean allowSnakeYamlWarnings = (config.get("openapi.parsing.warnings.enabled").asBoolean().orElse(false));
                if (SNAKE_YAML_INTROSPECTOR_LOGGER.isLoggable(Level.WARNING) && !allowSnakeYamlWarnings) {
                    SNAKE_YAML_INTROSPECTOR_LOGGER.setLevel(Level.SEVERE);
                }
                helper = SnakeYAMLParserHelper.create(ExpandedTypeDescription::create);
                adjustTypeDescriptions(helper.types());
            }
            return helper;
        });
    }

    static Map<Class<?>, ExpandedTypeDescription> buildImplsToTypes(SnakeYAMLParserHelper<ExpandedTypeDescription> helper) {
        return Collections.unmodifiableMap(helper.entrySet().stream()
                .map(Map.Entry::getValue)
                .collect(Collectors.toMap(ExpandedTypeDescription::impl, Function.identity())));
    }

    private static void adjustTypeDescriptions(Map<Class<?>, ExpandedTypeDescription> types) {
        /*
         * We need to adjust the {@code TypeDescription} objects set up by the generated {@code SnakeYAMLParserHelper} class
         * because there are some OpenAPI-specific issues that the general-purpose helper generator cannot know about.
         */

        /*
         * In the OpenAPI document, HTTP methods are expressed in lower-case. But the associated Java methods on the PathItem
         * class use the HTTP method names in upper-case. So for each HTTP method, "add" a property to PathItem's type
         * description using the lower-case name but upper-case Java methods and exclude the upper-case property that
         * SnakeYAML's automatic analysis of the class already created.
         */
        ExpandedTypeDescription pathItemTD = types.get(PathItem.class);
        for (PathItem.HttpMethod m : PathItem.HttpMethod.values()) {
            pathItemTD.substituteProperty(m.name().toLowerCase(), Operation.class, getter(m), setter(m));
            pathItemTD.addExcludes(m.name());
        }

        /*
         * An OpenAPI document can contain a property named "enum" for Schema and ServerVariable, but the related Java methods
         * use "enumeration".
         */
        Set.<Class<?>>of(Schema.class, ServerVariable.class).forEach(c -> {
            ExpandedTypeDescription tdWithEnumeration = types.get(c);
            tdWithEnumeration.substituteProperty("enum", List.class, "getEnumeration", "setEnumeration");
            tdWithEnumeration.addPropertyParameters("enum", String.class);
            tdWithEnumeration.addExcludes("enumeration");
        });

        /*
         * SnakeYAML derives properties only from methods declared directly by each OpenAPI interface, not from methods defined
         *  on other interfaces which the original one extends. Those we have to handle explicitly.
         */
        for (ExpandedTypeDescription td : types.values()) {
            if (Extensible.class.isAssignableFrom(td.getType())) {
                td.addExtensions();
            }
            Property defaultProperty = td.defaultProperty();
            if (defaultProperty != null) {
                td.substituteProperty("default", defaultProperty.getType(), "getDefaultValue", "setDefaultValue");
                td.addExcludes("defaultValue");
            }
            if (isRef(td)) {
                td.addRef();
            }
        }
    }

    private static boolean isRef(TypeDescription td) {
        for (Class<?> c : td.getType().getInterfaces()) {
            if (c.equals(Reference.class)) {
                return true;
            }
        }
        return false;
    }

    private static String getter(PathItem.HttpMethod method) {
        return methodName("get", method);
    }

    private static String setter(PathItem.HttpMethod method) {
        return methodName("set", method);
    }

    private static String methodName(String operation, PathItem.HttpMethod method) {
        return operation + method.name();
    }

    /**
     * Prepares the OpenAPI model that later will be used to create the OpenAPI
     * document for endpoints in this application.
     *
     * @param config {@code OpenApiConfig} object describing paths, servers, etc.
     * @param staticFile the static file, if any, to be included in the resulting model
     * @param filteredIndexViews possibly empty list of FilteredIndexViews to use in harvesting definitions from the code
     * @return the OpenAPI model
     * @throws RuntimeException in case of errors reading any existing static
     * OpenAPI document
     */
    private OpenAPI prepareModel(OpenApiConfig config, OpenApiStaticFile staticFile,
            List<? extends IndexView> filteredIndexViews) {
        try {
            // The write lock guarding the model has already been acquired.
            OpenApiDocument.INSTANCE.reset();
            OpenApiDocument.INSTANCE.config(config);
            OpenApiDocument.INSTANCE.modelFromReader(OpenApiProcessor.modelFromReader(config, getContextClassLoader()));
            if (staticFile != null) {
                OpenApiDocument.INSTANCE.modelFromStaticFile(OpenAPIParser.parse(helper().types(), staticFile.getContent(),
                        OpenAPIMediaType.byFormat(staticFile.getFormat())));
            }
            if (isAnnotationProcessingEnabled(config)) {
                expandModelUsingAnnotations(config, filteredIndexViews);
            } else {
                LOGGER.log(Level.FINE, "OpenAPI Annotation processing is disabled");
            }
            OpenApiDocument.INSTANCE.filter(OpenApiProcessor.getFilter(config, getContextClassLoader()));
            OpenApiDocument.INSTANCE.initialize();
            OpenAPIImpl instance = OpenAPIImpl.class.cast(OpenApiDocument.INSTANCE.get());

            // Create a copy, primarily to avoid problems during unit testing.
            // The SmallRye MergeUtil omits the openapi value, so we need to set it explicitly.
            return MergeUtil.merge(new OpenAPIImpl(), instance)
                    .openapi(instance.getOpenapi());
        } catch (IOException ex) {
            throw new RuntimeException("Error initializing OpenAPI information", ex);
        }
    }

    private boolean isAnnotationProcessingEnabled(OpenApiConfig config) {
        return !config.scanDisable();
    }

    private void expandModelUsingAnnotations(OpenApiConfig config, List<? extends IndexView> filteredIndexViews) {
        if (filteredIndexViews.isEmpty() || config.scanDisable()) {
            return;
        }

        /*
         * Conduct a SmallRye OpenAPI annotation scan for each filtered index view, merging the resulting OpenAPI models into one.
         * The AtomicReference is effectively final so we can update the actual reference from inside the lambda.
         */
        AtomicReference<OpenAPI> aggregateModelRef = new AtomicReference<>(new OpenAPIImpl()); // Start with skeletal model
        filteredIndexViews.forEach(filteredIndexView -> {
                OpenApiAnnotationScanner scanner = new OpenApiAnnotationScanner(config, filteredIndexView,
                        List.of(new HelidonAnnotationScannerExtension()));
                OpenAPI modelForApp = scanner.scan();
                if (LOGGER.isLoggable(Level.FINER)) {

                    LOGGER.log(Level.FINER, String.format("Intermediate model from filtered index view %s:%n%s",
                            filteredIndexView.getKnownClasses(), formatDocument(Format.YAML, modelForApp)));
                }
                aggregateModelRef.set(
                        MergeUtil.merge(aggregateModelRef.get(), modelForApp)
                                .openapi(modelForApp.getOpenapi())); // SmallRye's merge skips openapi value.

        });
        OpenApiDocument.INSTANCE.modelFromAnnotations(aggregateModelRef.get());
    }

    private static ClassLoader getContextClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    private static String typeFromPath(Path path) {
        final Path staticFileNamePath = path.getFileName();
        if (staticFileNamePath == null) {
            throw new IllegalArgumentException("File path "
                    + path.toAbsolutePath().toString()
                    + " does not seem to have a file name value but one is expected");
        }
        final String pathText = staticFileNamePath.toString();
        final String specifiedFileType = pathText.substring(pathText.lastIndexOf(".") + 1);
        return specifiedFileType;
    }

    private void prepareResponse(ServerRequest req, ServerResponse resp) {

        try {
            Optional<MediaType> requestedMediaType = chooseResponseMediaType(req);

            // Give the UI a chance to respond first if it claims to support the chosen media type.
            if (requestedMediaType.isPresent()
                && uiSupportsMediaType(requestedMediaType.get())) {
                if (ui.prepareTextResponseFromMainEndpoint(req, resp)) {
                    return;
                }
            }

            if (requestedMediaType.isEmpty()) {
                LOGGER.log(Level.FINER,
                           () -> String.format("Did not recognize requested media type %s; passing the request on",
                                               req.headers().acceptedTypes()));
                req.next();
                return;
           }

            MediaType resultMediaType = requestedMediaType.get();
            final String openAPIDocument = prepareDocument(resultMediaType);
            resp.status(Http.Status.OK_200);
            resp.headers().add(Http.Header.CONTENT_TYPE, resultMediaType.toString());
            resp.send(openAPIDocument);
        } catch (Exception ex) {
            resp.status(Http.Status.INTERNAL_SERVER_ERROR_500);
            resp.send("Error serializing OpenAPI document; " + ex.getMessage());
            LOGGER.log(Level.SEVERE, "Error serializing OpenAPI document", ex);
        }
    }

    private boolean uiSupportsMediaType(MediaType mediaType) {
        // The UI supports a very short list of media types, hence the sequential search.
        for (MediaType uiSupportedMediaType : mediaTypesSupportedByUi) {
            if (uiSupportedMediaType.test(mediaType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the OpenAPI document in the requested format.
     *
     * @param resultMediaType requested media type
     * @return String containing the formatted OpenAPI document
     * from its underlying data
     */
    String prepareDocument(MediaType resultMediaType) {
        OpenAPIMediaType matchingOpenAPIMediaType
                = OpenAPIMediaType.byMediaType(resultMediaType)
                .orElseGet(() -> {
                    LOGGER.log(Level.FINER,
                            () -> String.format(
                                    "Requested media type %s not supported; using default",
                                    resultMediaType.toString()));
                    return OpenAPIMediaType.DEFAULT_TYPE;
                });


        final Format resultFormat = matchingOpenAPIMediaType.format();

        String result = cachedDocuments.computeIfAbsent(resultFormat,
                fmt -> {
                    String r = formatDocument(fmt);
                    LOGGER.log(Level.FINER,
                            "Created and cached OpenAPI document in {0} format",
                            fmt.toString());
                    return r;
                });
        return result;
    }

    private String formatDocument(Format fmt) {
        return formatDocument(fmt, model());
    }

    private String formatDocument(Format fmt, OpenAPI model) {
        StringWriter sw = new StringWriter();
        Serializer.serialize(helper().types(), implsToTypes, model, fmt, sw);
        return sw.toString();

    }

    private Optional<MediaType> chooseResponseMediaType(ServerRequest req) {
        /*
         * Response media type default is application/vnd.oai.openapi (YAML)
         * unless otherwise specified.
         */
        Optional<String> queryParameterFormat = req.queryParams()
                .first(OPENAPI_ENDPOINT_FORMAT_QUERY_PARAMETER);
        if (queryParameterFormat.isPresent()) {
            String queryParameterFormatValue = queryParameterFormat.get();
            try {
                return Optional.of(QueryParameterRequestedFormat.chooseFormat(queryParameterFormatValue).mediaType());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Query parameter 'format' had value '"
                        + queryParameterFormatValue
                        + "' but expected " + Arrays.toString(QueryParameterRequestedFormat.values()));
            }
        }

        RequestHeaders headers = req.headers();
        if (headers.acceptedTypes().isEmpty()) {
            headers.add(Http.Header.ACCEPT, DEFAULT_RESPONSE_MEDIA_TYPE.toString());
        }
        return headers
                .bestAccepted(preferredMediaTypeOrdering);
    }

    /**
     * Extension we want SmallRye's OpenAPI implementation to use for parsing the JSON content in Extension annotations.
     */
    private static class HelidonAnnotationScannerExtension implements AnnotationScannerExtension {

        @Override
        public Object parseExtension(String key, String value) {

            // Inspired by SmallRye's JsonUtil#parseValue method.
            if (value == null) {
                return null;
            }

            value = value.trim();

            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                return Boolean.valueOf(value);
            }

            // See if we should parse the value fully.
            switch (value.charAt(0)) {
                case '{':
                case '[':
                case '-':
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    try {
                        JsonReader reader = JSON_READER_FACTORY.createReader(new StringReader(value));
                        JsonValue jsonValue = reader.readValue();
                        return convertJsonValue(jsonValue);
                    } catch (Exception ex) {
                        LOGGER.log(Level.SEVERE, String.format("Error parsing extension key: %s, value: %s", key, value), ex);
                    }
                    break;

                default:
                    break;
            }

            // Treat as JSON string.
            return value;
        }

        private static Object convertJsonValue(JsonValue jsonValue) {
            switch (jsonValue.getValueType()) {
                case ARRAY:
                    JsonArray jsonArray = jsonValue.asJsonArray();
                    return jsonArray.stream()
                            .map(OpenAPISupport.HelidonAnnotationScannerExtension::convertJsonValue)
                            .collect(Collectors.toList());

                case FALSE:
                    return Boolean.FALSE;

                case TRUE:
                    return Boolean.TRUE;

                case NULL:
                    return null;

                case STRING:
                    return JsonString.class.cast(jsonValue).getString();

                case NUMBER:
                    JsonNumber jsonNumber = JsonNumber.class.cast(jsonValue);
                    return jsonNumber.numberValue();

                case OBJECT:
                    JsonObject jsonObject = jsonValue.asJsonObject();
                    return jsonObject.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, entry -> convertJsonValue(entry.getValue())));

                default:
                    return jsonValue.toString();
            }
        }
    }

    /**
     * Abstraction of the different representations of a static OpenAPI document
     * file and the file type(s) they correspond to.
     * <p>
     * Each {@code OpenAPIMediaType} stands for a single format (e.g., yaml,
     * json). That said, each can map to multiple file types (e.g., yml and
     * yaml) and multiple actual media types (the proposed OpenAPI media type
     * vnd.oai.openapi and various other YAML types proposed or in use).
     */
    enum OpenAPIMediaType {

        JSON(Format.JSON,
             new MediaType[] {MediaType.APPLICATION_OPENAPI_JSON,
                     MediaType.APPLICATION_JSON},
             "json"),
        YAML(Format.YAML,
             new MediaType[] {MediaType.APPLICATION_OPENAPI_YAML,
                     MediaType.APPLICATION_X_YAML,
                     MediaType.APPLICATION_YAML,
                     MediaType.TEXT_PLAIN,
                     MediaType.TEXT_X_YAML,
                     MediaType.TEXT_YAML},
             "yaml", "yml");

        private static final OpenAPIMediaType DEFAULT_TYPE = YAML;

        static final String TYPE_LIST = "json|yaml|yml"; // must be a true constant so it can be used in an annotation

        private final Format format;
        private final List<String> fileTypes;
        private final List<MediaType> mediaTypes;

        OpenAPIMediaType(Format format, MediaType[] mediaTypes, String... fileTypes) {
            this.format = format;
            this.mediaTypes = Arrays.asList(mediaTypes);
            this.fileTypes = new ArrayList<>(Arrays.asList(fileTypes));
        }

        private Format format() {
            return format;
        }

        List<String> matchingTypes() {
            return fileTypes;
        }

        private static OpenAPIMediaType byFileType(String fileType) {
            for (OpenAPIMediaType candidateType : values()) {
                if (candidateType.matchingTypes().contains(fileType)) {
                    return candidateType;
                }
            }
            return null;
        }

        private static Optional<OpenAPIMediaType> byMediaType(MediaType mt) {
            for (OpenAPIMediaType candidateType : values()) {
                if (candidateType.mediaTypes.contains(mt)) {
                    return Optional.of(candidateType);
                }
            }
            return Optional.empty();
        }

        private static List<String> recognizedFileTypes() {
            final List<String> result = new ArrayList<>();
            for (OpenAPIMediaType type : values()) {
                result.addAll(type.fileTypes);
            }
            return result;
        }

        private static OpenAPIMediaType byFormat(Format format) {
            for (OpenAPIMediaType candidateType : values()) {
                if (candidateType.format.equals(format)) {
                    return candidateType;
                }
            }
            return null;
        }

        private static final MediaType[] NON_TEXT_PREFERRED_ORDERING =
                new MediaType[] {
                        MediaType.APPLICATION_OPENAPI_YAML,
                        MediaType.APPLICATION_X_YAML,
                        MediaType.APPLICATION_YAML,
                        MediaType.APPLICATION_OPENAPI_JSON,
                        MediaType.APPLICATION_JSON,
                        MediaType.TEXT_X_YAML,
                        MediaType.TEXT_YAML

                };
    }

    /**
     * Creates a new {@link Builder} for {@code OpenAPISupport} using defaults.
     *
     * @return new Builder
     */
    public static SEOpenAPISupportBuilder builder() {
        return builderSE();
    }

    /**
     * Creates a new {@link OpenAPISupport} instance using defaults.
     *
     * @return new OpenAPISUpport
     */
    public static OpenAPISupport create() {
        return builderSE().build();
    }

    /**
     * Creates a new {@link OpenAPISupport} instance using the
     * 'openapi' portion of the provided
     * {@link Config} object.
     *
     * @param config {@code Config} object containing OpenAPI-related settings
     * @return new {@code OpenAPISupport} instance created using the
     * helidonConfig settings
     */
    public static OpenAPISupport create(Config config) {
        return builderSE().config(config).build();
    }

    /**
     * Returns an OpenAPISupport.Builder for Helidon SE environments.
     *
     * @return Helidon SE {@code OpenAPISupport.Builder}
     */
    static SEOpenAPISupportBuilder builderSE() {
        return new SEOpenAPISupportBuilder();
    }

    /**
     * Fluent API builder for {@link OpenAPISupport}.
     * <p>
     * This abstract implementation is extended once for use by developers from
     * Helidon SE apps and once for use from the Helidon MP-provided OpenAPI
     * service. This lets us constrain what use cases are possible from each
     * (for example, no anno processing from SE).
     *
     * @param <B> concrete subclass of OpenAPISupport.Builder
     */
    @Configured(description = "OpenAPI support configuration")
    public abstract static class Builder<B extends Builder<B>> implements io.helidon.common.Builder<B, OpenAPISupport> {

        /**
         * Config key to select the openapi node from Helidon config.
         */
        public static final String CONFIG_KEY = "openapi";

        private Optional<String> webContext = Optional.empty();
        private Optional<String> staticFilePath = Optional.empty();
        private CrossOriginConfig crossOriginConfig = null;

        private OpenApiUi.Builder<?, ?> uiBuilder = OpenApiUi.builder();

        /**
         * Set various builder attributes from the specified {@code Config} object.
         * <p>
         * The {@code Config} object can specify web-context and static-file in addition to settings
         * supported by {@link OpenAPIConfigImpl.Builder}.
         *
         * @param config the openapi {@code Config} object possibly containing settings
         * @exception NullPointerException if the provided {@code Config} is null
         * @return updated builder instance
         */
        public B config(Config config) {
            config.get("web-context")
                    .asString()
                    .ifPresent(this::webContext);
            config.get("static-file")
                    .asString()
                    .ifPresent(this::staticFile);
            config.get(CORS_CONFIG_KEY)
                    .as(CrossOriginConfig::create)
                    .ifPresent(this::crossOriginConfig);
            config.get(OpenApiUi.Builder.OPENAPI_UI_CONFIG_KEY)
                    .ifExists(uiBuilder::config);
            return identity();
        }

        /**
         * Returns the web context (path) at which the OpenAPI endpoint should
         * be exposed, either the most recent explicitly-set value via
         * {@link #webContext(java.lang.String)} or the default
         * {@value #DEFAULT_WEB_CONTEXT}.
         *
         * @return path the web context path for the OpenAPI endpoint
         */
        String webContext() {
            String webContextPath = webContext.orElse(DEFAULT_WEB_CONTEXT);
            if (webContext.isPresent()) {
                LOGGER.log(Level.FINE, "OpenAPI path set to {0}", webContextPath);
            } else {
                LOGGER.log(Level.FINE, "OpenAPI path defaulting to {0}", webContextPath);
            }
            return webContextPath;
        }

        /**
         * Returns the path to a static OpenAPI document file (if any exists),
         * either as explicitly set using {@link #staticFile(java.lang.String) }
         * or one of the default files.
         *
         * @return the OpenAPI static file instance for the static file if such
         * a file exists, null otherwise
         */
        OpenApiStaticFile staticFile() {
            return staticFilePath.isPresent() ? getExplicitStaticFile() : getDefaultStaticFile();
        }

        /**
         * Returns the smallrye OpenApiConfig instance describing the set-up
         * that will govern the smallrye OpenAPI behavior.
         *
         * @return {@code OpenApiConfig} conveying how OpenAPI should behave
         */
        public abstract OpenApiConfig openAPIConfig();

        /**
         * Makes sure the set-up for OpenAPI is consistent, internally and with
         * the current Helidon runtime environment (SE or MP).
         *
         * @throws IllegalStateException if validation fails
         */
        public void validate() throws IllegalStateException {
        }

        /**
         * Sets the web context path for the OpenAPI endpoint.
         *
         * @param path webContext to use, defaults to
         * {@value DEFAULT_WEB_CONTEXT}
         * @return updated builder instance
         */
        @ConfiguredOption(DEFAULT_WEB_CONTEXT)
        public B webContext(String path) {
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            this.webContext = Optional.of(path);
            return identity();
        }

        /**
         * Sets the file system path of the static OpenAPI document file. Default types are `json`, `yaml`, and `yml`.
         *
         * @param path non-null location of the static OpenAPI document file
         * @return updated builder instance
         */
        @ConfiguredOption(value = DEFAULT_STATIC_FILE_PATH_PREFIX + "*")
        public B staticFile(String path) {
            Objects.requireNonNull(path, "path to static file must be non-null");
            staticFilePath = Optional.of(path);
            return identity();
        }

        /**
         * Assigns the CORS settings for the OpenAPI endpoint.
         *
         * @param crossOriginConfig {@code CrossOriginConfig} containing CORS set-up
         * @return updated builder instance
         */
        @ConfiguredOption(key = CORS_CONFIG_KEY)
        public B crossOriginConfig(CrossOriginConfig crossOriginConfig) {
            Objects.requireNonNull(crossOriginConfig, "CrossOriginConfig must be non-null");
            this.crossOriginConfig = crossOriginConfig;
            return identity();
        }

        /**
         * Assigns the OpenAPI UI builder the {@code OpenAPISupport} service should use in preparing the UI.
         *
         * @param uiBuilder the {@link OpenApiUi.Builder}
         * @return updated builder instance
         */
        @ConfiguredOption(type = OpenApiUi.class)
        public B ui(OpenApiUi.Builder<?, ?> uiBuilder) {
            Objects.requireNonNull(uiBuilder, "UI must be non-null");
            this.uiBuilder = uiBuilder;
            return identity();
        }

        /**
         * Returns the supplier of index views.
         *
         * @return index views supplier
         */
        protected Supplier<List<? extends IndexView>> indexViewsSupplier() {
            // Only in MP can we have possibly multiple index views, one per app, from scanning classes (or the Jandex index).
            return () -> Collections.emptyList();
        }

        private OpenApiStaticFile getExplicitStaticFile() {
            Path path = Paths.get(staticFilePath.get());
            final String specifiedFileType = typeFromPath(path);
            final OpenAPIMediaType specifiedMediaType = OpenAPIMediaType.byFileType(specifiedFileType);

            if (specifiedMediaType == null) {
                throw new IllegalArgumentException("OpenAPI file path "
                        + path.toAbsolutePath().toString()
                        + " is not one of recognized types: "
                        + OpenAPIMediaType.recognizedFileTypes());
            }
            final InputStream is;
            try {
                is = new BufferedInputStream(Files.newInputStream(path));
            } catch (IOException ex) {
                throw new IllegalArgumentException("OpenAPI file "
                        + path.toAbsolutePath().toString()
                        + " was specified but was not found", ex);
            }

            try {
                LOGGER.log(Level.FINE,
                        () -> String.format(
                                OPENAPI_EXPLICIT_STATIC_FILE_LOG_MESSAGE_FORMAT,
                                path.toAbsolutePath().toString()));
                return new OpenApiStaticFile(is, specifiedMediaType.format());
            } catch (Exception ex) {
                try {
                    is.close();
                } catch (IOException ioex) {
                    ex.addSuppressed(ioex);
                }
                throw ex;
            }
        }

        private OpenApiStaticFile getDefaultStaticFile() {
            final List<String> candidatePaths = LOGGER.isLoggable(Level.FINER) ? new ArrayList<>() : null;
            for (OpenAPIMediaType candidate : OpenAPIMediaType.values()) {
                for (String type : candidate.matchingTypes()) {
                    String candidatePath = DEFAULT_STATIC_FILE_PATH_PREFIX + type;
                    InputStream is = null;
                    try {
                        is = getContextClassLoader().getResourceAsStream(candidatePath);
                        if (is != null) {
                            Path path = Paths.get(candidatePath);
                            LOGGER.log(Level.FINE, () -> String.format(
                                    OPENAPI_DEFAULTED_STATIC_FILE_LOG_MESSAGE_FORMAT,
                                    path.toAbsolutePath().toString()));
                            return new OpenApiStaticFile(is, candidate.format());
                        }
                        if (candidatePaths != null) {
                            candidatePaths.add(candidatePath);
                        }
                    } catch (Exception ex) {
                        if (is != null) {
                            try {
                                is.close();
                            } catch (IOException ioex) {
                                ex.addSuppressed(ioex);
                            }
                        }
                        throw ex;
                    }
                }
            }
            if (candidatePaths != null) {
                LOGGER.log(Level.FINER,
                        candidatePaths.stream()
                                .collect(Collectors.joining(
                                        ",",
                                        "No default static OpenAPI description file found; checked [",
                                        "]")));
            }
            return null;
        }
    }

    private static <T> T access(Lock guard, Supplier<T> operation) {
        guard.lock();
        try {
            return operation.get();
        } finally {
            guard.unlock();
        }
    }
}
