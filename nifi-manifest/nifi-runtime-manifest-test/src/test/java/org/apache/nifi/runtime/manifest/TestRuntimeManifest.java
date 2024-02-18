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
package org.apache.nifi.runtime.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.nifi.c2.protocol.component.api.BuildInfo;
import org.apache.nifi.c2.protocol.component.api.Bundle;
import org.apache.nifi.c2.protocol.component.api.ComponentManifest;
import org.apache.nifi.c2.protocol.component.api.ProcessorDefinition;
import org.apache.nifi.c2.protocol.component.api.PropertyDescriptor;
import org.apache.nifi.c2.protocol.component.api.ReportingTaskDefinition;
import org.apache.nifi.c2.protocol.component.api.RuntimeManifest;
import org.apache.nifi.c2.protocol.component.api.SchedulingDefaults;
import org.apache.nifi.scheduling.SchedulingStrategy;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRuntimeManifest {

    public static final String LIST_HDFS_DEFAULT_SCHEDULE_TIME = "1 min";

    @Test
    void testRuntimeManifest() throws IOException {
        final ObjectMapper objectMapper = new ObjectMapper();

        final RuntimeManifest runtimeManifest;
        try (final InputStream inputStream = new FileInputStream("target/nifi-runtime-manifest/nifi-runtime-manifest.json")) {
            runtimeManifest = objectMapper.readValue(inputStream, RuntimeManifest.class);
        }
        assertNotNull(runtimeManifest);
        assertEquals("apache-nifi", runtimeManifest.getIdentifier());
        assertEquals("nifi", runtimeManifest.getAgentType());
        assertNotNull(runtimeManifest.getVersion());

        final BuildInfo buildInfo = runtimeManifest.getBuildInfo();
        assertNotNull(buildInfo);
        assertNotNull(buildInfo.getCompiler());
        assertNotNull(buildInfo.getRevision());
        assertNotNull(buildInfo.getTimestamp());
        assertNotNull(buildInfo.getVersion());

        final SchedulingDefaults schedulingDefaults = runtimeManifest.getSchedulingDefaults();
        assertNotNull(schedulingDefaults);
        assertEquals(SchedulingStrategy.TIMER_DRIVEN, schedulingDefaults.getDefaultSchedulingStrategy());

        final Map<String, Integer> defaultConcurrentTasks = schedulingDefaults.getDefaultConcurrentTasksBySchedulingStrategy();
        assertNotNull(defaultConcurrentTasks);
        assertEquals(3, defaultConcurrentTasks.size());
        assertEquals(SchedulingStrategy.TIMER_DRIVEN.getDefaultConcurrentTasks(), defaultConcurrentTasks.get(SchedulingStrategy.TIMER_DRIVEN.name()).intValue());
        assertEquals(SchedulingStrategy.EVENT_DRIVEN.getDefaultConcurrentTasks(), defaultConcurrentTasks.get(SchedulingStrategy.EVENT_DRIVEN.name()).intValue());
        assertEquals(SchedulingStrategy.CRON_DRIVEN.getDefaultConcurrentTasks(), defaultConcurrentTasks.get(SchedulingStrategy.CRON_DRIVEN.name()).intValue());

        final Map<String, String> defaultSchedulingPeriods = schedulingDefaults.getDefaultSchedulingPeriodsBySchedulingStrategy();
        assertEquals(2, defaultSchedulingPeriods.size());
        assertEquals(SchedulingStrategy.TIMER_DRIVEN.getDefaultSchedulingPeriod(), defaultSchedulingPeriods.get(SchedulingStrategy.TIMER_DRIVEN.name()));
        assertEquals(SchedulingStrategy.CRON_DRIVEN.getDefaultSchedulingPeriod(), defaultSchedulingPeriods.get(SchedulingStrategy.CRON_DRIVEN.name()));

        final List<Bundle> bundles = runtimeManifest.getBundles();
        assertNotNull(bundles);
        assertTrue(bundles.size() > 0);


        // Verify ExecuteSQL has readsAttributes
        final ProcessorDefinition executeSqlDef = getProcessorDefinition(bundles, "nifi-standard-nar",
                "org.apache.nifi.processors.standard.ExecuteSQL");
        assertNotNull(executeSqlDef.getReadsAttributes());
        assertFalse(executeSqlDef.getReadsAttributes().isEmpty());
        assertNotNull(executeSqlDef.getReadsAttributes().get(0).getName());
        assertNotNull(executeSqlDef.getReadsAttributes().get(0).getDescription());
        assertTrue(executeSqlDef.getSupportsSensitiveDynamicProperties());

        // Verify RouteOnAttribute dynamic relationships and dynamic properties
        final ProcessorDefinition routeOnAttributeDef = getProcessorDefinition(bundles, "nifi-standard-nar",
                "org.apache.nifi.processors.standard.RouteOnAttribute");

        assertTrue(routeOnAttributeDef.getSupportsDynamicRelationships());
        assertNotNull(routeOnAttributeDef.getDynamicRelationship());
        assertNotNull(routeOnAttributeDef.getDynamicRelationship().getName());
        assertNotNull(routeOnAttributeDef.getDynamicRelationship().getDescription());

        assertTrue(routeOnAttributeDef.getSupportsDynamicProperties());
        assertFalse(routeOnAttributeDef.getSupportsSensitiveDynamicProperties());
        assertNotNull(routeOnAttributeDef.getDynamicProperties());
        assertFalse(routeOnAttributeDef.getDynamicProperties().isEmpty());
        assertNotNull(routeOnAttributeDef.getDynamicProperties().get(0).getName());
        assertNotNull(routeOnAttributeDef.getDynamicProperties().get(0).getDescription());
        assertNotNull(routeOnAttributeDef.getDynamicProperties().get(0).getValue());
        assertNotNull(routeOnAttributeDef.getDynamicProperties().get(0).getExpressionLanguageScope());

        // Verify DeleteAzureBlobStorage is deprecated
        final ProcessorDefinition deleteAzureBlobDef = getProcessorDefinition(bundles, "nifi-azure-nar",
                "org.apache.nifi.processors.azure.storage.DeleteAzureBlobStorage");
        assertNotNull(deleteAzureBlobDef.getDeprecated());
        assertTrue(deleteAzureBlobDef.getDeprecated().booleanValue());
        assertNotNull(deleteAzureBlobDef.getDeprecationReason());
        assertNotNull(deleteAzureBlobDef.getDeprecationAlternatives());
        assertFalse(deleteAzureBlobDef.getDeprecationAlternatives().isEmpty());

        // Verify SplitJson has @SystemResourceConsiderations
        final ProcessorDefinition splitJsonDef = getProcessorDefinition(bundles, "nifi-standard-nar",
                "org.apache.nifi.processors.standard.SplitJson");
        assertNotNull(splitJsonDef.getSystemResourceConsiderations());
        assertFalse(splitJsonDef.getSystemResourceConsiderations().isEmpty());
        assertNotNull(splitJsonDef.getSystemResourceConsiderations().get(0).getResource());
        assertNotNull(splitJsonDef.getSystemResourceConsiderations().get(0).getDescription());
    }

    private PropertyDescriptor getPropertyDescriptor(final ProcessorDefinition processorDefinition, final String propName) {
        final Map<String, PropertyDescriptor> propertyDescriptors = processorDefinition.getPropertyDescriptors();
        assertNotNull(propertyDescriptors);

        final PropertyDescriptor propertyDescriptor = propertyDescriptors.values().stream()
                .filter(p -> p.getName().equals(propName))
                .findFirst()
                .orElse(null);
        assertNotNull(propertyDescriptor);
        return propertyDescriptor;
    }

    private ProcessorDefinition getProcessorDefinition(final List<Bundle> bundles, final String artifactId, final String type) {
        final ComponentManifest componentManifest = getComponentManifest(bundles, artifactId);

        final List<ProcessorDefinition> processors = componentManifest.getProcessors();
        assertNotNull(processors);

        final ProcessorDefinition processorDefinition = processors.stream()
                .filter(p -> p.getType().equals(type))
                .findFirst()
                .orElse(null);
        assertNotNull(processorDefinition);
        return processorDefinition;
    }

    private ReportingTaskDefinition getReportingTaskDefinition(final List<Bundle> bundles, final String artifactId, final String type) {
        final ComponentManifest componentManifest = getComponentManifest(bundles, artifactId);

        final List<ReportingTaskDefinition> reportingTasks = componentManifest.getReportingTasks();
        assertNotNull(reportingTasks);

        final ReportingTaskDefinition reportingTaskDefinition = reportingTasks.stream()
                .filter(p -> p.getType().equals(type))
                .findFirst()
                .orElse(null);
        assertNotNull(reportingTaskDefinition);
        return reportingTaskDefinition;
    }

    private ComponentManifest getComponentManifest(final List<Bundle> bundles, final String artifactId) {
        final Bundle bundle = bundles.stream().filter(b -> b.getArtifact().equals(artifactId)).findFirst().orElse(null);
        assertNotNull(bundle);

        final ComponentManifest componentManifest = bundle.getComponentManifest();
        assertNotNull(componentManifest);
        return componentManifest;
    }
}
