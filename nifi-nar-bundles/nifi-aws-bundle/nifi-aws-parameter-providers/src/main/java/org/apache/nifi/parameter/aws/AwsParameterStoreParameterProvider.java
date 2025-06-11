/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.parameter.aws;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.http.conn.ssl.SdkTLSSocketFactory;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterResult;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathResult;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.parameter.AbstractParameterProvider;
import org.apache.nifi.parameter.Parameter;
import org.apache.nifi.parameter.ParameterDescriptor;
import org.apache.nifi.parameter.ParameterGroup;
import org.apache.nifi.parameter.VerifiableParameterProvider;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderService;
import org.apache.nifi.ssl.SSLContextService;

import javax.net.ssl.SSLContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Tags({"aws", "parameterstore", "ssm", "parameters"})
@CapabilityDescription("Fetches parameters from AWS Systems Manager Parameter Store. Parameters can be fetched individually or by path. " +
        "When fetching by path, the path becomes the Parameter group name, and all parameters under that path become Parameters in the group.")
public class AwsParameterStoreParameterProvider extends AbstractParameterProvider implements VerifiableParameterProvider {

    public static final PropertyDescriptor PARAMETER_NAME = new PropertyDescriptor.Builder()
            .name("parameter-name")
            .displayName("Parameter Name")
            .description("The name of the parameter to fetch. Will be used instead of the Parameter Path property if set.")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(false)
            .build();

    public static final PropertyDescriptor PARAMETER_PATH = new PropertyDescriptor.Builder()
            .name("parameter-path")
            .displayName("Parameter Path")
            .description("The path prefix to fetch parameters from. All parameters under this path will be fetched. " +
                    "This parameter is ignored if the Parameter Name parameter is set.")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .defaultValue("/")
            .build();

    public static final PropertyDescriptor RECURSIVE = new PropertyDescriptor.Builder()
            .name("recursive")
            .displayName("Recursive")
            .description("If true, will fetch parameters recursively under the specified path. If false, will only fetch parameters directly under the path.")
            .required(true)
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();

    public static final PropertyDescriptor AWS_CREDENTIALS_PROVIDER_SERVICE = new PropertyDescriptor.Builder()
            .name("aws-credentials-provider-service")
            .displayName("AWS Credentials Provider Service")
            .description("Service used to obtain an Amazon Web Services Credentials Provider")
            .required(true)
            .identifiesControllerService(AWSCredentialsProviderService.class)
            .build();

    public static final PropertyDescriptor REGION = new PropertyDescriptor.Builder()
            .name("aws-region")
            .displayName("Region")
            .required(true)
            .allowableValues(getAvailableRegions())
            .defaultValue(createAllowableValue(Regions.DEFAULT_REGION).getValue())
            .build();

    public static final PropertyDescriptor TIMEOUT = new PropertyDescriptor.Builder()
            .name("aws-communications-timeout")
            .displayName("Communications Timeout")
            .required(true)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .defaultValue("30 secs")
            .build();

    public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
            .name("aws-ssl-context-service")
            .displayName("SSL Context Service")
            .description("Specifies an optional SSL Context Service that, if provided, will be used to create connections")
            .required(false)
            .identifiesControllerService(SSLContextService.class)
            .build();

    private static final String DEFAULT_USER_AGENT = "NiFi";
    private static final Protocol DEFAULT_PROTOCOL = Protocol.HTTPS;
    private static final List<PropertyDescriptor> PROPERTIES = Collections.unmodifiableList(Arrays.asList(
            PARAMETER_NAME,
            PARAMETER_PATH,
            RECURSIVE,
            REGION,
            AWS_CREDENTIALS_PROVIDER_SERVICE,
            TIMEOUT,
            SSL_CONTEXT_SERVICE
    ));

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    public List<ParameterGroup> fetchParameters(final ConfigurationContext context) {
        AWSSimpleSystemsManagement ssmClient = configureClient(context);
        final List<ParameterGroup> groups = new ArrayList<>();

        // Check if parameter-name is set and return the single parameter if it is
        final PropertyValue parameterNameProp = context.getProperty(PARAMETER_NAME);
        if (parameterNameProp.isSet()) {
            groups.addAll(fetchParameter(ssmClient, parameterNameProp.getValue()));
        } else {
            // if parameter-name is not set, fetch parameters by path
            final String path = context.getProperty(PARAMETER_PATH).getValue();
            final boolean recursive = context.getProperty(RECURSIVE).asBoolean();
            groups.addAll(fetchParametersByPath(ssmClient, path, recursive));
        }

        return groups;
    }

    @Override
    public List<ConfigVerificationResult> verify(final ConfigurationContext context, final ComponentLog verificationLogger) {
        final List<ConfigVerificationResult> results = new ArrayList<>();

        try {
            final List<ParameterGroup> parameterGroups = fetchParameters(context);
            int parameterCount = 0;
            for (final ParameterGroup group : parameterGroups) {
                parameterCount += group.getParameters().size();
            }
            results.add(new ConfigVerificationResult.Builder()
                    .outcome(ConfigVerificationResult.Outcome.SUCCESSFUL)
                    .verificationStepName("Fetch Parameters")
                    .explanation(String.format("Fetched [%d] parameters across [%d] groups",
                            parameterCount, parameterGroups.size()))
                    .build());
        } catch (final Exception e) {
            verificationLogger.error("Failed to fetch parameters", e);
            results.add(new ConfigVerificationResult.Builder()
                    .outcome(ConfigVerificationResult.Outcome.FAILED)
                    .verificationStepName("Fetch Parameters")
                    .explanation("Failed to fetch parameters: " + e.getMessage())
                    .build());
        }
        return results;
    }

    private List<ParameterGroup> fetchParameter(final AWSSimpleSystemsManagement ssmClient, final String parameterName) {
        final List<ParameterGroup> groups = new ArrayList<>();
        final List<Parameter> parameters = new ArrayList<>();

        try {
            final GetParameterRequest request = new GetParameterRequest()
                    .withName(parameterName)
                    .withWithDecryption(true);

            final GetParameterResult result = ssmClient.getParameter(request);
            final com.amazonaws.services.simplesystemsmanagement.model.Parameter ssmParameter = result.getParameter();

            if (ssmParameter != null) {
                final String name = ssmParameter.getName();
                final String value = ssmParameter.getValue();
                parameters.add(createParameter(name, value));
                groups.add(new ParameterGroup(name, parameters));
            }

            return groups;
        } catch (final Exception e) {
            throw new IllegalStateException("Error retrieving parameter " + parameterName, e);
        }
    }

    private List<ParameterGroup> fetchParametersByPath(final AWSSimpleSystemsManagement ssmClient, final String path, final boolean recursive) {
        final List<ParameterGroup> groups = new ArrayList<>();
        final List<Parameter> parameters = new ArrayList<>();

        try {
            GetParametersByPathRequest request = new GetParametersByPathRequest()
                    .withPath(path)
                    .withRecursive(recursive)
                    .withWithDecryption(true);

            GetParametersByPathResult result;
            do {
                result = ssmClient.getParametersByPath(request);
                
                for (final com.amazonaws.services.simplesystemsmanagement.model.Parameter ssmParameter : result.getParameters()) {
                    final String name = ssmParameter.getName();
                    final String value = ssmParameter.getValue();
                    parameters.add(createParameter(name, value));
                }

                request.setNextToken(result.getNextToken());
            } while (result.getNextToken() != null);

            if (!parameters.isEmpty()) {
                groups.add(new ParameterGroup(path, parameters));
            }

            return groups;
        } catch (final Exception e) {
            throw new IllegalStateException("Error retrieving parameters from path " + path, e);
        }
    }

    private Parameter createParameter(final String parameterName, final String parameterValue) {
        final ParameterDescriptor parameterDescriptor = new ParameterDescriptor.Builder().name(parameterName).build();
        return new Parameter(parameterDescriptor, parameterValue, null, true);
    }

    protected ClientConfiguration createConfiguration(final ConfigurationContext context) {
        final ClientConfiguration config = new ClientConfiguration();
        config.setMaxErrorRetry(0);
        config.setUserAgentPrefix(DEFAULT_USER_AGENT);
        config.setProtocol(DEFAULT_PROTOCOL);
        final int commsTimeout = context.getProperty(TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS).intValue();
        config.setConnectionTimeout(commsTimeout);
        config.setSocketTimeout(commsTimeout);

        final SSLContextService sslContextService = context.getProperty(SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);
        if (sslContextService != null) {
            final SSLContext sslContext = sslContextService.createContext();
            SdkTLSSocketFactory sdkTLSSocketFactory = new SdkTLSSocketFactory(sslContext, SdkTLSSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER);
            config.getApacheHttpClientConfig().setSslSocketFactory(sdkTLSSocketFactory);
        }

        return config;
    }

    AWSSimpleSystemsManagement configureClient(final ConfigurationContext context) {
        return AWSSimpleSystemsManagementClientBuilder.standard()
                .withRegion(context.getProperty(REGION).getValue())
                .withClientConfiguration(createConfiguration(context))
                .withCredentials(getCredentialsProvider(context))
                .build();
    }

    protected AWSCredentialsProvider getCredentialsProvider(final ConfigurationContext context) {
        final AWSCredentialsProviderService awsCredentialsProviderService =
                context.getProperty(AWS_CREDENTIALS_PROVIDER_SERVICE).asControllerService(AWSCredentialsProviderService.class);
        return awsCredentialsProviderService.getCredentialsProvider();
    }

    private static AllowableValue createAllowableValue(final Regions region) {
        return new AllowableValue(region.getName(), region.getDescription(), "AWS Region Code : " + region.getName());
    }

    private static AllowableValue[] getAvailableRegions() {
        final List<AllowableValue> values = new ArrayList<>();
        for (final Regions region : Regions.values()) {
            values.add(createAllowableValue(region));
        }
        return values.toArray(new AllowableValue[0]);
    }
} 