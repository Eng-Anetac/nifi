package org.apache.nifi.processors.aws.dynamodb;

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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tags({"Amazon", "DynamoDB", "AWS", "Put", "Insert", "Record"})
public class UpdateDynamoDBRecord extends AbstractAwsProcessor<DynamoDbClient, DynamoDbClientBuilder> {
    public static final PropertyDescriptor TABLE = new PropertyDescriptor.Builder()
            .name("table_name")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .description("The DynamoDB table name")
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
            CREATED_AT_FIELD,
            new PropertyDescriptor.Builder().fromPropertyDescriptor(AWS_CREDENTIALS_PROVIDER_SERVICE).required(true).build(),
            REGION,
            TABLE);

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
            final RecordReader reader = recordParserFactory.createRecordReader(flowFile, in, getLogger());
            Map<String, AttributeValue> expressionValues = new HashMap<>();
            Record record;
            while ((record = reader.nextRecord()) != null) {
                Map<String, AttributeValue> item = new HashMap<>();
                Map<String, Object> recordMap = record.toMap();
                for(Map.Entry<String, Object> entry : recordMap.entrySet()) {
                    item.put(entry.getKey(), AttributeValue.fromS(entry.getValue().toString()));
                }

                UpdateItemRequest.Builder requestBuilder = UpdateItemRequest.builder().tableName(tableName).key(item);
                if(createdAtField != null) {
                    requestBuilder = requestBuilder.updateExpression(String.format("SET %s = if_not_exists(%s, :ca)",
                            createdAtField, createdAtField));
                    expressionValues.put(":ca", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
                    requestBuilder = requestBuilder.expressionAttributeValues(expressionValues);
                }
                UpdateItemRequest request = requestBuilder.build();
                client.updateItem(request);
            }
        } catch (MalformedRecordException | IOException | SchemaNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
