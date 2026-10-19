import java.util.List;

/**
 * CommandRouter routes parsed command arguments to the StorageEngine
 * and returns RESP-formatted response strings.
 */
public class CommandRouter {

    private static final StorageEngine storage = StorageEngine.getInstance();

    public static String execute(List<String> command) {
        if (command == null || command.isEmpty()) {
            return RespProtocol.error("empty command");
        }

        String cmd = command.get(0).toUpperCase();

        switch (cmd) {
            case "PING":
                return RespProtocol.simpleString("PONG");

            case "SET":
                if (command.size() < 3) {
                    return RespProtocol.error("wrong number of arguments for 'SET'");
                }

                String key = command.get(1);
                String value = command.get(2);

                // Check for EX option: SET key value EX seconds
                if (command.size() == 5 && command.get(3).equalsIgnoreCase("EX")) {
                    try {
                        int seconds = Integer.parseInt(command.get(4));
                        storage.set(key, value, seconds);
                    } catch (NumberFormatException e) {
                        return RespProtocol.error("value is not an integer or out of range");
                    }
                } else {
                    storage.set(key, value);
                }
                return RespProtocol.simpleString("OK");

            case "GET":
                if (command.size() < 2) {
                    return RespProtocol.error("wrong number of arguments for 'GET'");
                }
                return RespProtocol.bulkString(storage.get(command.get(1)));

            case "DEL":
                if (command.size() < 2) {
                    return RespProtocol.error("wrong number of arguments for 'DEL'");
                }
                return RespProtocol.integer(storage.delete(command.get(1)) ? 1 : 0);

            case "EXISTS":
                if (command.size() < 2) {
                    return RespProtocol.error("wrong number of arguments for 'EXISTS'");
                }
                return RespProtocol.integer(storage.exists(command.get(1)) ? 1 : 0);

            case "KEYS":
                return RespProtocol.array(storage.keys());

            case "INCR":
                if (command.size() < 2) {
                    return RespProtocol.error("wrong number of arguments for 'INCR'");
                }
                try {
                    return RespProtocol.integer(storage.incr(command.get(1)));
                } catch (NumberFormatException e) {
                    return RespProtocol.error("value is not an integer or out of range");
                }

            case "DECR":
                if (command.size() < 2) {
                    return RespProtocol.error("wrong number of arguments for 'DECR'");
                }
                try {
                    return RespProtocol.integer(storage.decr(command.get(1)));
                } catch (NumberFormatException e) {
                    return RespProtocol.error("value is not an integer or out of range");
                }

            case "TTL":
                if (command.size() < 2) {
                    return RespProtocol.error("wrong number of arguments for 'TTL'");
                }
                return RespProtocol.integer(storage.ttl(command.get(1)));

            default:
                return RespProtocol.error("unknown command '" + cmd + "'");
        }
    }
}
