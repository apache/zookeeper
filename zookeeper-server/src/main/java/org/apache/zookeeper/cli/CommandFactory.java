package org.apache.zookeeper.cli;

import org.apache.zookeeper.server.admin.Command;

import java.util.function.Supplier;

/**
 * Factory class for creating instances of {@code CliCommand}.
 */
public class CommandFactory {

    /**
     * All Cli Commands.
     */
    public enum Command {
        CLOSE(CloseCommand::new),
        CREATE(CreateCommand::new),
        DELETE(DeleteCommand::new),
        DELETE_ALL(DeleteAllCommand::new),
        SET(SetCommand::new),
        GET(GetCommand::new),
        LS(LsCommand::new),
        GET_ACL(GetAclCommand::new),
        SET_ACL(SetAclCommand::new),
        STAT(StatCommand::new),
        SYNC(SyncCommand::new),
        SET_QUOTA(SetQuotaCommand::new),
        LIST_QUOTA(ListQuotaCommand::new),
        DEL_QUOTA(DelQuotaCommand::new),
        ADD_AUTH(AddAuthCommand::new),
        RECONFIG(ReconfigCommand::new),
        GET_CONFIG(GetConfigCommand::new),
        REMOVE_WATCHES(RemoveWatchesCommand::new),
        GET_EPHEMERALS(GetEphemeralsCommand::new),
        GET_ALL_CHILDREN_NUMBER(GetAllChildrenNumberCommand::new),
        VERSION(VersionCommand::new),
        ADD_WATCH(AddWatchCommand::new);

        private Supplier<? extends CliCommand> instantiator;

        private CliCommand getInstance() {
            return instantiator.get();
        }

        Command(Supplier<? extends CliCommand> instantiator) {
            this.instantiator = instantiator;
        }
    }

    /**
     * Creates a new {@Code CliCommand} instance.
     * @param command the
     * @return the new instance
     */
    public static CliCommand getInstance (Command command) {
        return command.getInstance();
    }
}
