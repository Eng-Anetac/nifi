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
package org.apache.nifi.processors.aws.dynamodb;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.aws.v2.AbstractAwsProcessor;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.MalformedRecordException;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.record.Record;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tags({"Amazon", "DynamoDB", "AWS", "Put", "Insert", "Record", "Update"})
@CapabilityDescription("Inserts or updates records in DynamoDB. Sets created_at_field only if it is not present in the record.")
public class UpdateDynamoDBRecord extends AbstractAwsProcessor<DynamoDbClient, DynamoDbClientBuilder> {
    public static final PropertyDescriptor TABLE = new PropertyDescriptor.Builder()
            .name("table_name")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description("The DynamoDB table name")
            .build();

    static final PropertyDescriptor PARTITION_KEY_FIELD = new PropertyDescriptor.Builder()
            .name("partition-key-field")
            .displayName("Partition Key Field")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description(
                    "Defines the name of the partition key field in the DynamoDB table. Partition key is also known as hash key. " +
                            "Depending on the \"Partition Key Strategy\" the field value might come from the incoming Record or a generated one.")
            .build();
    public static final PropertyDescriptor CREATED_AT_FIELD = new PropertyDescriptor.Builder()
            .name("created_at_field")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description("Field that holds the timestamp when record is created")
            .build();

    static final PropertyDescriptor RECORD_READER = new PropertyDescriptor.Builder()
            .name("record-reader")
            .displayName("Record Reader")
            .description("Specifies the Controller Service to use for parsing incoming data and determining the data's schema.")
            .identifiesControllerService(RecordReaderFactory.class)
            .required(true)
            .build();

    private static final List<PropertyDescriptor> PROPERTIES = Arrays.asList(
            RECORD_READER,
            PARTITION_KEY_FIELD,
            CREATED_AT_FIELD,
            REGION,
            TABLE,
            new PropertyDescriptor.Builder().fromPropertyDescriptor(AWS_CREDENTIALS_PROVIDER_SERVICE).required(true).build(),
            TIMEOUT,
            SSL_CONTEXT_SERVICE,
            PROXY_HOST,
            PROXY_HOST_PORT,
            PROXY_USERNAME,
            PROXY_PASSWORD,
            PROXY_CONFIGURATION_SERVICE
            );

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    protected DynamoDbClientBuilder createClientBuilder(ProcessContext processContext) {
        return DynamoDbClient.builder();
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }
        final RecordReaderFactory recordParserFactory = context.getProperty(RECORD_READER).asControllerService(RecordReaderFactory.class);
        final InputStream in = session.read(flowFile);
        try {
            DynamoDbClient client = getClient(context, flowFile);
            final String tableName = context.getProperty(TABLE).evaluateAttributeExpressions(flowFile).getValue();
            String createdAtField = context.getProperty(CREATED_AT_FIELD).evaluateAttributeExpressions(flowFile).getValue();
            String partitionKey = context.getProperty(PARTITION_KEY_FIELD).evaluateAttributeExpressions(flowFile).getValue();
            final RecordReader reader = recordParserFactory.createRecordReader(flowFile, in, getLogger());
            Map<String, AttributeValue> expressionValues = new HashMap<>();
            Map<String, String> expressionNames = new HashMap<>();
            Record record;
            while ((record = reader.nextRecord()) != null) {
                Map<String, AttributeValue> itemKey = new HashMap<>();
                List<String> updateExpressions = new ArrayList<>();
                if(createdAtField != null) {
                    updateExpressions.add(String.format("%s = if_not_exists(%s, :ca)", createdAtField, createdAtField));
                    expressionValues.put(":ca", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
                }
                int i = 0;
                for(String fieldName : record.getRawFieldNames()) {
                    if(fieldName.equalsIgnoreCase(partitionKey)) {
                        itemKey.put(fieldName, AttributeValue.builder().s(record.getAsString(fieldName)).build());
                    } else {
                        // work around reserved words like 'name' and 'timestamp'
                        String attrName = "#attr" + i;
                        String valName = ":val" + i;
                        updateExpressions.add((String.format("%s = %s", attrName, valName)));
                        expressionValues.put(valName, AttributeValue.builder().s(record.getAsString(fieldName)).build());
                        expressionNames.put(attrName, fieldName);
                    }
                    i++;
                }
                UpdateItemRequest.Builder requestBuilder = UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(itemKey);
                requestBuilder = requestBuilder.updateExpression("SET " + String.join(", ", updateExpressions));
                requestBuilder = requestBuilder.expressionAttributeValues(expressionValues)
                        .expressionAttributeNames(expressionNames);

                UpdateItemRequest request = requestBuilder.build();
                UpdateItemResponse response = client.updateItem(request);
                //check if update was successful
                if(response.sdkHttpResponse().isSuccessful()) {
                    getLogger().info("Successfully updated item in DynamoDB table {} with key {}", tableName, itemKey);
                } else {
                    getLogger().error("Error while updating item in DynamoDB table {} with key {}: {}", tableName, itemKey, response.sdkHttpResponse().statusText().get());
                    session.transfer(flowFile, REL_FAILURE);
                    return;
                }
            }
            session.transfer(flowFile, REL_SUCCESS);
        } catch (MalformedRecordException | IOException | SchemaNotFoundException e) {
            getLogger().error("Error while reading records: " + e.getMessage(), e);
            session.transfer(flowFile, REL_FAILURE);
        }
    }
}
