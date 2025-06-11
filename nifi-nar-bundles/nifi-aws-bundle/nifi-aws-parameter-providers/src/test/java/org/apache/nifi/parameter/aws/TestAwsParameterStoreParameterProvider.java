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

import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterResult;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathResult;
import com.amazonaws.services.simplesystemsmanagement.model.Parameter;
import com.amazonaws.services.simplesystemsmanagement.model.ParameterNotFoundException;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.parameter.ParameterGroup;
import org.apache.nifi.parameter.Parameter;
import org.apache.nifi.parameter.ParameterDescriptor;
import org.apache.nifi.parameter.VerifiableParameterProvider;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockComponentLog;
import org.apache.nifi.util.MockConfigurationContext;
import org.apache.nifi.util.MockParameterProviderInitializationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TestAwsParameterStoreParameterProvider {

    @Mock
    private AWSSimpleSystemsManagement ssmClient;

    private static Parameter parameter(final String name, final String value) {
        return new Parameter(new ParameterDescriptor.Builder().name(name).build(), value);
    }

    @Test
    public void testFetchSingleParameter() throws InitializationException {
        final String paramName = "/myapp/db/password";
        final String paramValue = "secret123";
        com.amazonaws.services.simplesystemsmanagement.model.Parameter ssmParam =
                new com.amazonaws.services.simplesystemsmanagement.model.Parameter()
                        .withName(paramName).withValue(paramValue);
        GetParameterResult getResult = new GetParameterResult().withParameter(ssmParam);
        when(ssmClient.getParameter(argThat(new GetParameterRequestMatcher(paramName)))).thenReturn(getResult);

        AwsParameterStoreParameterProvider provider = spy(new AwsParameterStoreParameterProvider());
        doReturn(ssmClient).when(provider).configureClient(any());
        provider.initialize(new MockParameterProviderInitializationContext("id", "name", new MockComponentLog("providerId", provider)));

        Map<PropertyDescriptor, String> properties = new HashMap<>();
        properties.put(AwsParameterStoreParameterProvider.PARAMETER_NAME, paramName);
        MockConfigurationContext context = new MockConfigurationContext(properties, null);

        List<ParameterGroup> groups = provider.fetchParameters(context);
        assertEquals(1, groups.size());
        assertEquals(paramName, groups.get(0).getGroupName());
        assertEquals(1, groups.get(0).getParameters().size());
        assertEquals(paramValue, groups.get(0).getParameters().get(0).getValue());
    }

    @Test
    public void testFetchParametersByPath() throws InitializationException {
        final String path = "/myapp/";
        List<com.amazonaws.services.simplesystemsmanagement.model.Parameter> ssmParams = Arrays.asList(
                new com.amazonaws.services.simplesystemsmanagement.model.Parameter().withName("/myapp/db/user").withValue("user1"),
                new com.amazonaws.services.simplesystemsmanagement.model.Parameter().withName("/myapp/db/password").withValue("pass1")
        );
        GetParametersByPathResult byPathResult = new GetParametersByPathResult().withParameters(ssmParams);
        when(ssmClient.getParametersByPath(argThat(new GetParametersByPathRequestMatcher(path, true)))).thenReturn(byPathResult);

        AwsParameterStoreParameterProvider provider = spy(new AwsParameterStoreParameterProvider());
        doReturn(ssmClient).when(provider).configureClient(any());
        provider.initialize(new MockParameterProviderInitializationContext("id", "name", new MockComponentLog("providerId", provider)));

        Map<PropertyDescriptor, String> properties = new HashMap<>();
        properties.put(AwsParameterStoreParameterProvider.PARAMETER_PATH, path);
        properties.put(AwsParameterStoreParameterProvider.RECURSIVE, "true");
        MockConfigurationContext context = new MockConfigurationContext(properties, null);

        List<ParameterGroup> groups = provider.fetchParameters(context);
        assertEquals(1, groups.size());
        assertEquals(path, groups.get(0).getGroupName());
        assertEquals(2, groups.get(0).getParameters().size());
    }

    @Test
    public void testFetchParameterNotFound() throws InitializationException {
        final String paramName = "/notfound";
        when(ssmClient.getParameter(argThat(new GetParameterRequestMatcher(paramName)))).thenThrow(new ParameterNotFoundException("not found"));

        AwsParameterStoreParameterProvider provider = spy(new AwsParameterStoreParameterProvider());
        doReturn(ssmClient).when(provider).configureClient(any());
        provider.initialize(new MockParameterProviderInitializationContext("id", "name", new MockComponentLog("providerId", provider)));

        Map<PropertyDescriptor, String> properties = new HashMap<>();
        properties.put(AwsParameterStoreParameterProvider.PARAMETER_NAME, paramName);
        MockConfigurationContext context = new MockConfigurationContext(properties, null);

        assertThrows(RuntimeException.class, () -> provider.fetchParameters(context));
    }

    @Test
    public void testFetchParametersByPathWithNextToken() throws InitializationException {
        final String path = "/myapp/";
        List<com.amazonaws.services.simplesystemsmanagement.model.Parameter> ssmParams1 = Collections.singletonList(
                new com.amazonaws.services.simplesystemsmanagement.model.Parameter().withName("/myapp/db/user").withValue("user1")
        );
        List<com.amazonaws.services.simplesystemsmanagement.model.Parameter> ssmParams2 = Collections.singletonList(
                new com.amazonaws.services.simplesystemsmanagement.model.Parameter().withName("/myapp/db/password").withValue("pass1")
        );
        GetParametersByPathResult byPathResult1 = new GetParametersByPathResult().withParameters(ssmParams1).withNextToken("token1");
        GetParametersByPathResult byPathResult2 = new GetParametersByPathResult().withParameters(ssmParams2);
        when(ssmClient.getParametersByPath(argThat(new GetParametersByPathRequestMatcher(path, true, null)))).thenReturn(byPathResult1);
        when(ssmClient.getParametersByPath(argThat(new GetParametersByPathRequestMatcher(path, true, "token1")))).thenReturn(byPathResult2);

        AwsParameterStoreParameterProvider provider = spy(new AwsParameterStoreParameterProvider());
        doReturn(ssmClient).when(provider).configureClient(any());
        provider.initialize(new MockParameterProviderInitializationContext("id", "name", new MockComponentLog("providerId", provider)));

        Map<PropertyDescriptor, String> properties = new HashMap<>();
        properties.put(AwsParameterStoreParameterProvider.PARAMETER_PATH, path);
        properties.put(AwsParameterStoreParameterProvider.RECURSIVE, "true");
        MockConfigurationContext context = new MockConfigurationContext(properties, null);

        List<ParameterGroup> groups = provider.fetchParameters(context);
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).getParameters().size());
    }

    private static class GetParameterRequestMatcher implements ArgumentMatcher<GetParameterRequest> {
        private final String name;
        GetParameterRequestMatcher(String name) { this.name = name; }
        @Override
        public boolean matches(GetParameterRequest argument) {
            return argument != null && argument.getName().equals(name);
        }
    }

    private static class GetParametersByPathRequestMatcher implements ArgumentMatcher<GetParametersByPathRequest> {
        private final String path;
        private final boolean recursive;
        private final String nextToken;
        GetParametersByPathRequestMatcher(String path, boolean recursive) {
            this(path, recursive, null);
        }
        GetParametersByPathRequestMatcher(String path, boolean recursive, String nextToken) {
            this.path = path;
            this.recursive = recursive;
            this.nextToken = nextToken;
        }
        @Override
        public boolean matches(GetParametersByPathRequest argument) {
            if (argument == null) return false;
            if (!Objects.equals(argument.getPath(), path)) return false;
            if (argument.getRecursive() != recursive) return false;
            if (nextToken == null) return argument.getNextToken() == null;
            return nextToken.equals(argument.getNextToken());
        }
    }
} 