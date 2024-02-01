package org.apache.nifi.toolkit.cli.impl.command.nifi.tenants;

import org.apache.commons.cli.MissingOptionException;
import org.apache.nifi.toolkit.cli.api.Context;
import org.apache.nifi.toolkit.cli.impl.client.nifi.NiFiClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.NiFiClientException;
import org.apache.nifi.toolkit.cli.impl.client.nifi.TenantsClient;
import org.apache.nifi.toolkit.cli.impl.command.CommandOption;
import org.apache.nifi.toolkit.cli.impl.command.nifi.AbstractNiFiCommand;
import org.apache.nifi.toolkit.cli.impl.result.StringResult;
import org.apache.nifi.web.api.dto.UserDTO;
import org.apache.nifi.web.api.entity.UserEntity;

import java.io.IOException;
import java.util.Properties;

public class DeleteUser extends AbstractNiFiCommand<StringResult> {

        public DeleteUser() {
            super("delete-user", StringResult.class);
        }

        @Override
        public String getDescription() {
            return "Deletes the user with the given id.";
        }

        @Override
        protected void doInitialize(final Context context) {
            addOption(CommandOption.USER_ID.createOption());
        }

        @Override
        public StringResult doExecute(final NiFiClient client, final Properties properties)
                throws NiFiClientException, IOException, MissingOptionException {

            final String userId = getRequiredArg(properties, CommandOption.USER_ID);
            UserEntity userEntity = client.getTenantsClient().getUser(userId);
            if (userEntity == null) {
                throw new NiFiClientException("User with id " + userId + " does not exist");
            }
            final TenantsClient tenantsClient = client.getTenantsClient();
            tenantsClient.deleteUser(userEntity);
            return new StringResult(userId, getContext().isInteractive());
        }
}
