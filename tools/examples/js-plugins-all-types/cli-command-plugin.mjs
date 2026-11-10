export default {
  id: "js-cli-command-plugin",
  name: "JS CLI Command Plugin",
  version: "1.0.0",
  type: "CLI_COMMAND",

  initialize(context) {
    return true;
  },

  shutdown() {},

  cliCommands() {
    return [{ name: "js-example", help: "Example JS plugin command", subcommands: [] }];
  },

  // Optional: makes the listed commands runnable. Answers { exitCode, stdout: [lines], stderr: [lines] }.
  runCliCommand(command, args) {
    return { exitCode: 0, stdout: [`Hello from ${command}: ${args.join(" ")}`], stderr: [] };
  }
};
