# LogGraph README

## 1 - About
LogGraph is an application for viewing and filtering zookeeper logs. It can handle transaction logs and message logs. 

## 2 - Compiling

Run `mvn clean install dependency:copy-dependencies` in zookeeper-contrib/zookeeper-contrib-loggraph/.
This will download all dependencies and compile all the loggraph code.

Once compilation has finished, you can run it the the `loggraph.sh` script in zookeeper-contrib/zookeeper-contrib-loggraph/src/main/resources.
This will start and embedded web server on your machine.
Navigate to `http://localhost:8182/graph/main.html`

## 3 - Usage
LogGraph presents the user with 4 views, 
 
  a) Simple log view
     This view simply displays the log text. This isn't very useful without filters (see "Filtering the logs").

  b) Server view
     The server view shows the interactions between the different servers in an ensemble. The X axis represents time.
      
    * Exceptions show up as red dots. Hovering your mouse over them will give you more details of the exception
	* The colour of the line represents the election state of the server. 
	   - orange means LOOKING for leader
	   - dark green means the server is the leader
	   - light green means the server is following a leader
	   - yellow means there isn't enough information to determine the state of the server. 
	* The gray arrows denote election messages between servers. Pink dashed arrows are messages that were sent but never delivered.

  c) Session view
     The session view shows the lifetime of sessions on a server. Use the time filter to narrow down the view. Any more than about 2000 events will take a long time to view in your browser. 
     The X axis represents time. Each line is a session. The black dots represent events on the session. You can click on the black dots for more details of the event.

  d) Stats view
     There is currently only one statistics view, Transactions/minute. Suggestions for other statistic views are very welcome.

## 4 - Filtering the logs
The logs can be filtered in 2 ways, by time and by content. 

To filter by time simply move the slider to the desired start time. The time window specifies how many milliseconds after and including the start time will be displayed.

Content filtering uses a adhoc filtering language, using prefix notation. The language looks somewhat similar to lisp. A statement in the language takes the form (op arg arg ....). A statement resolves to a boolean value. Statements can be nested. 

### 4.1 - Filter arguments
An argument can be a number, a string or a symbol. A number is any argument which starts with -, + or 0 to 9. If the number starts with 0x it is interpretted as hexidecimal. Otherwise it is interpretted as decimal. If the argument begins with a double-quote, (") it is interpretted as a string. Anything else is interpretted as a symbol.

### 4.2 - Filter symbols
The possible filter symbols are: 

client-id : number, the session id of the client who initiated a transaction.

cxid : number, the cxid of a transaction

zxid : number, the zxid of a transaction

operation : string, the operation being performed, for example "setData", "createSession", "closeSession", "error", "create"

### 4.3 - Filter operations
The possible filter operations are:

or : logical or, takes 1 or more arguments which must be other statements.

and : logical and, takes 1 or more arguments which must be other statements.

not : logical not, takes 1 argument which must be another statement.

xor : exclusive or, takes 1 or more arguments which must be other statements.

 = : equals, takes 1 or more arguments, which must all be equal to each other to return true.

&gt; : greater than, takes 1 or more arguments, to return true the 1st argument must be greater than the 2nd argument which must be greater than the 3rd argument and so on... 

&lt; : less than, takes 1 or more arguments, to return true the 1st argument must be less than the 2nd argument which must be less than the 3rd argument and so on... 

### 4.4 - Filter examples
Give me all the setData operations with session id 0xdeadbeef or 0xcafeb33r but not with zxid 0x12341234 ->

(and (= operation "setData") (or (= client-id 0xdeadbeef) (= client-id 0xcafeb33r)) (not (= zxid 0x12341234)))
