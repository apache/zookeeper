package org.apache.zookeeper.cli;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Unit test for {@link CommandFactory}.
 */
public class CommandFactoryTest {

    /**
     * Verify that the {@code CommandFactory} can create a command instance.
     */
    @Test
    public void testCommandCreation() {
        CliCommand cliCommand =
                CommandFactory.getInstance(CommandFactory.Command.CREATE);
        assertTrue(cliCommand instanceof CreateCommand);
    }
}
