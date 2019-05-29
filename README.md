# Cooperative Routing Protocol Demo
This program is a sample implementation of the Cooperative Routing Protocol, which is explained in [this paper](report.pdf).

## Features
### Network initialization
The program supports two different network initialization modes.

#### Initialize the network randomly
The nodes can be created randomly by passing the following command-line arguments.

`-N:<number of nodes> -Conn:<minimum degree of a node>,<maximum degree of a node>`

#### Initialize the network from a file
The nodes can be loaded from a file that has the following syntax.
The file path should be supplied to the program as a command-line argument
  and it can be given relative to the project root folder.

```
// Anything that comes after a double slash is ignored.
# The same is true for a hash as well.

// Empty lines are ignored, such as the line above.
// If a line consists of only white space, it is considered empty.
// Empty lines are usually used to separate different test cases.

// A graph is defined using the following command.
# <node> <node> <weight> -> Create a link between the specified nodes, which has the specified weight.
# node: \w+
# weight: <number> | inf
# number: \d+(\.\d+)?

// An example graph definition
A B 1
B C 2
C D 3
D A 4

// Some test cases can be defined after the graph definition. The first test case must
// precede an empty line in order to differentiate it from the graph definition.
// Note that the line can only contain white space.

// List of commands that can be used in a test case
# Link manipulation:  <node> <node> <weight>          -> The same command used in the graph definition
# Node addition:      <node> join (<node> <weight>)*  -> Create a node with the specified id and links
# Node removal:       <node> leave                    -> Terminate the specified node
# Wait:               wait <number>                   -> Waits for the specified number of seconds

// Example test cases
wait 2      // Wait for convergence after initializing the network

A B inf     // The link between nodes A and B is congested
wait 5      // Wait for convergence
A B 1       // The link is restored
wait 5

B C inf     // The link between nodes B and C is congested
wait 15     // The link is broken due to continuining unresponsiveness

A leave     // Node A leaves the network
wait 5

E join B 2  // Node E joins the network with a link to B with weight 2
```

### Interactive shell
The program employs a command-line interface (CLI) to interact with the network.
All commands that can be put in a test file are accepted by the CLI.
The `Enter` key executes the specified command. To enter multiple commands into the command prompt,
  `Shift + Enter` can be used.

The output screen is color-coded. Green fields indicate the updated nodes,
  whereas blue fields indicate the nodes that needs to be informed.
If the interactive shell is not lauched, the output will be redirected to the console and
  the fields that need color-coding will be prepended with a star (*).

The flow of information is always from right to left,
  i.e. the left node is always the receiver and the right one is the sender.
The following symbols do not indicate the flow of information.

| Symbol | Meaning |
|:------:|:--------|
| `<<` | The receiver is updated |
| `<>` | The receiver is updated and the sender needs to be informed |
| `>>` | The sender needs to be informed |

## Run
Java 8 is required to compile and run the program.
