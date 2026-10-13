package dev.mars.swarm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SwarmConsoleTest {
    @Test
    void consoleManagesTwoSwarmsSendsCommandsAndShowsTestProgress() throws Exception {
        String output = run("""
                start alpha 2
                start beta 1
                list
                status alpha
                send alpha {"instruction":"math.add","parameters":{"a":10,"b":20}}
                send beta Hello beta
                test alpha 20 5
                stop alpha
                status beta
                stop-all
                list
                quit
                """);
        assertTrue(output.contains("Swarm started | name=alpha | servers=2"), output);
        assertTrue(output.contains("serverId=alpha-Server-002"), output);
        assertTrue(output.contains("result=30"), output);
        assertTrue(output.contains("result=\"Received: Hello beta\""), output);
        assertTrue(output.contains("completed=20/20"), output);
        assertTrue(output.contains("Swarm test PASSED | swarm=alpha | requests=20 | passed=20 | failed=0"), output);
        assertTrue(output.contains("alive=true"), output);
        assertTrue(output.contains("All swarms stopped"), output);
        assertTrue(output.contains("No running swarms"), output);
        assertFalse(output.contains("Error:"), output);
        System.out.println("Swarm console test PASSED");
    }

    @Test
    void invalidCommandsReportErrorsAndConsoleContinues() throws Exception {
        String output = run("""
                start alpha nope
                start alpha
                status missing
                test missing 0
                send alpha
                nonsense
                help
                quit extra
                list
                quit
                """);
        assertTrue(output.contains("Server count must be an integer"), output);
        assertTrue(output.contains("Usage: start"), output);
        assertTrue(output.contains("Unknown swarm: missing"), output);
        assertTrue(output.contains("Requests must be between"), output);
        assertTrue(output.contains("Usage: send"), output);
        assertTrue(output.contains("Unknown command; enter help"), output);
        assertTrue(output.contains("Usage: quit"), output);
        assertTrue(output.contains("No running swarms"), output);
    }

    @Test
    void eofReturnsAndOwnerClosesSwarms() throws Exception {
        var manager = new SwarmManager();
        try (manager) {
            SwarmMain.runConsole(manager, new BufferedReader(new StringReader("start eof 1\n")),
                    new PrintWriter(new StringWriter(), true));
            assertEquals(1, manager.list().size());
        }
        assertTrue(manager.list().isEmpty());
    }

    private static String run(String input) throws Exception {
        var output = new StringWriter();
        try (var manager = new SwarmManager()) {
            SwarmMain.runConsole(manager, new BufferedReader(new StringReader(input)), new PrintWriter(output, true));
        }
        return output.toString();
    }
}
