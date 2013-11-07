# Net::ZooKeeper - Perl extension for Apache ZooKeeper
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

use 5.008_008;

use strict;
use warnings;

package Net::ZooKeeper;

require Exporter;
require XSLoader;

our $VERSION = '0.35';

our @ISA = qw(Exporter);

our %EXPORT_TAGS = (
    'errors' => [qw(
        ZOK
        ZSYSTEMERROR
        ZRUNTIMEINCONSISTENCY
        ZDATAINCONSISTENCY
        ZCONNECTIONLOSS
        ZMARSHALLINGERROR
        ZUNIMPLEMENTED
        ZOPERATIONTIMEOUT
        ZBADARGUMENTS
        ZINVALIDSTATE
        ZAPIERROR
        ZNONODE
        ZNOAUTH
        ZBADVERSION
        ZNOCHILDRENFOREPHEMERALS
        ZNODEEXISTS
        ZNOTEMPTY
        ZSESSIONEXPIRED
        ZINVALIDCALLBACK
        ZINVALIDACL
        ZAUTHFAILED
        ZCLOSING
        ZNOTHING
    )],
    'node_flags' => [qw(
        ZOO_EPHEMERAL
        ZOO_SEQUENCE
    )],
    'acl_perms' => [qw(
        ZOO_PERM_READ
        ZOO_PERM_WRITE
        ZOO_PERM_CREATE
        ZOO_PERM_DELETE
        ZOO_PERM_ADMIN
        ZOO_PERM_ALL
    )],
    'acls' => [qw(
        ZOO_OPEN_ACL_UNSAFE
        ZOO_READ_ACL_UNSAFE
        ZOO_CREATOR_ALL_ACL
    )],
    'events' => [qw(
        ZOO_CREATED_EVENT
        ZOO_DELETED_EVENT
        ZOO_CHANGED_EVENT
        ZOO_CHILD_EVENT
        ZOO_SESSION_EVENT
        ZOO_NOTWATCHING_EVENT
    )],
    'states' => [qw(
        ZOO_EXPIRED_SESSION_STATE
        ZOO_AUTH_FAILED_STATE
        ZOO_CONNECTING_STATE
        ZOO_ASSOCIATING_STATE
        ZOO_CONNECTED_STATE
    )],
    'log_levels' => [qw(
        ZOO_LOG_LEVEL_OFF
        ZOO_LOG_LEVEL_ERROR
        ZOO_LOG_LEVEL_WARN
        ZOO_LOG_LEVEL_INFO
        ZOO_LOG_LEVEL_DEBUG
    )]
);

{
    my %tags;

    push @{$EXPORT_TAGS{'all'}},
        grep {!$tags{$_}++} @{$EXPORT_TAGS{$_}} foreach (keys(%EXPORT_TAGS));
}

our @EXPORT_OK = ( @{$EXPORT_TAGS{'all'}} );

XSLoader::load('Net::ZooKeeper', $VERSION);

1;

__END__

=head1 NAME

Net::ZooKeeper - Perl extension for Apache ZooKeeper

=head1 SYNOPSIS

  use Net::ZooKeeper qw(:node_flags :acls);

  my $zkh = Net::ZooKeeper->new('localhost:7000');

  $zkh->create('/foo', 'bar',
               'flags' => ZOO_EPHEMERAL,
               'acl' => ZOO_OPEN_ACL_UNSAFE) or
    die("unable to create node /foo: " . $zkh->get_error() . "\n");

  print "node /foo has value: " . $zkh->get('/foo') . "\n";

  $zkh->set('/foo', 'baz');

  print "node / has child nodes:\n";
  foreach my $path ($zkh->get_children('/')) {
    print "  /$path\n";
  }

  my $stat = $zkh->stat();
  if ($zkh->exists('/foo', 'stat' => $stat)) {
    print "node /foo has stat info:\n";
    while (my($key,$value) = each(%{$stat})) {
      print "  $key: $value\n";
    }
  }

  foreach my $acl_entry ($zkh->get_acl('/foo')) {
    print "node /foo has ACL entry:\n";
    print "  perms:  $acl_entry->{perms}\n";
    print "  scheme: $acl_entry->{scheme}\n";
    print "  id:     $acl_entry->{id}\n";
  }

  my $watch = $zkh->watch('timeout' => 10000);
  $zkh->exists('/foo', 'watch' => $watch);

  if ($watch->wait()) {
    print "watch triggered on node /foo:\n";
    print "  event: $watch->{event}\n";
    print "  state: $watch->{state}\n";
  }
  else {
    print "watch timed out after 10 seconds\n";
  }

  $zkh->delete('/foo');

=head1 DESCRIPTION

Net::ZooKeeper provides a Perl interface to the synchronous C API
of Apache ZooKeeper.  ZooKeeper is coordination service for
distributed applications and is a sub-project of the Apache Hadoop
project.

Each connection to ZooKeeper is represented as a handle object
of the class Net::ZooKeeper, similar to the manner in which database
connections are represented in the DBI module.

To disconnect from ZooKeeper, simply destroy the Net::ZooKeeper
handle object by undefining it or by explicitly calling the
C<DESTROY()> method.

The methods which may be invoked on Net::ZooKeeper handles
correspond to the functions of the synchronous ZooKeeper C API;
e.g., the Net::ZooKeeper method C<create()> calls the ZooKeeper
C function C<zoo_create()>, C<delete()> calls C<zoo_delete()>,
and so forth.

The synchronous API functions wait for a response from the ZooKeeper
cluster before returning a result to the caller.  Using these
functions permits Net::ZooKeeper to provide an interface similar
to that of a DBI driver module.

=head2 Internal POSIX Threads

The use of the synchronous ZooKeeper C API still requires that
the ZooKeeper C client code create several POSIX threads which run
concurrently with the main thread containing the Perl interpreter.

The synchronous API functions are wrappers of the asynchronous
functions in the ZooKeeper C API.  When a request is made by the
caller's thread (i.e., the one with the running Perl interpreter),
it is enqueued for delivery at a later time by the ZooKeeper C client
code's IO thread.  The caller's thread then waits for notification
before returning from the synchronous API function.

The IO thread dequeues the request and sends it to the ZooKeeper
cluster, while also ensuring that a regular "heartbeat" is maintained
with the cluster so that the current session does not time out.
When the IO thread receives a response from
the ZooKeeper cluster, it enqueues the response for delivery to the
client by the second thread of the ZooKeeper client code, the
completion thread.

If the caller is using the asynchronous API, the completion thread
invokes the appropriate callback function provided by the caller
for the given request.  In the case of Net::ZooKeeper, it is not
viable for the completion thread to invoke a Perl callback function
at arbitrary times; this could interfere with the state of the
Perl interpreter.

For this reason Net::ZooKeeper uses the synchronous API only.  After
enqueuing requests the synchronous API functions wait for notification
of the corresponding response.  The completion thread delivers these
notifications, at which point the synchronous functions return to
their caller.

Note that the IO and completion threads are POSIX threads, not
Perl ithreads.  Net::ZooKeeper defined a C<CLONE_SKIP()> function so
that if Perl ithreads are spawned while a Net::ZooKeeper connection
is active, the Net::ZooKeeper handle objects inherited by the
spawned ithread contain undefined values so that they can not be used.
Thus each ithread will need to create its own private connections to a
ZooKeeper cluster.

Note also that before invoking C<fork()> to spawn a new process,
all Net::ZooKeeper handles should be destroyed so that all
connections to ZooKeeper are closed and all internal POSIX threads
have exited.  If a child process needs to communicate with
ZooKeeper it should open its own private connections after it is
created by C<fork()>.

=head2 Signals

The ZooKeeper C API uses TCP connections to communicate with
the ZooKeeper cluster.  These connections may generate SIGPIPE
signals when they encounter errors, such as when a connection
is terminated by a ZooKeeper server.  Therefore most applications
will want to trap or ignore SIGPIPE signals, e.g.:

  local $SIG{'PIPE'} = 'IGNORE';

Ignoring SIGPIPE signals (or providing a signal handler that returns
control to the interrupted program after receiving the signal)
will allow the ZooKeeper C client code to detect the connection error
and report it upon return from the next Net::ZooKeeper method.

=head2 Error Handling

Net::ZooKeeper methods return different values in the case of an
error depending on their purpose and context.  For example,
C<exists()> returns true if the node exists and false otherwise,
which may indicate either that the node does not exist or that
an error occurred.

After any method returns a false, empty, or undefined value which
might indicate an error has occurred, the C<get_error()> method
may be called to examine the specific error code, if any.

If C<get_error()> returns C<ZOK>, no error has occurred.  If the
error code is less than C<ZAPIERROR>, it indicates a normal error
condition reported by the ZooKeeper server, such as C<ZNONODE>
(node does not exist) or C<ZNODEEXISTS> (node already exists).

If the error code is greater than C<ZAPIERROR>, then a connection
error or server error has occurred and the client should probably
close the connection by undefining the Net::ZooKeeper handle object
and, if necessary, attempt to create a new connection to the
ZooKeeper cluster.

=head2 Access Control

If the ZooKeeper cluster is not configured with C<skipACL=yes> then
it will respect the access controls set for each node in the
ZooKeeper hierarchy.  These access controls are defined using ACLs
(Access Control Lists); see the ZooKeeper documentation for compete
details.

In Net::ZooKeeper, ACLs are represented as arrays of hashes, where
each hash is an ACL entry that must contain three attributes,
C<perms>, C<scheme>, and C<id>.  The C<perms> attribute's value
should be composed by combining ACL permission flags using the
bitwise OR operator.  See C<:acl_perms> for a list of the
available ACL permission flags.

The ACL for a node may be read using the C<get_acl()> method.  A
node's ACL may be set when the node is created by passing an ACL
array as the value of the C<'acl'> option to the C<create()> method,
and may be updated by passing an ACL array to the C<set_acl()> method.

When a client connects to a ZooKeeper cluster it is automatically
assigned authentication credentials based on its IP address.
Additional authentication credentials may be added using
the C<add_auth()> method.  Once a credential has been added for
the current session, there is no way to disable it.

As an example, digest authentication may be enabled for a session
by calling C<add_auth()> as follows:

  $zkh->add_auth('digest', "$username:$password");

Note that the username and password are transmitted in cleartext
to the ZooKeeper cluster.

Such authentication credentials would enable access to a node
whose ACL contained an entry with a C<scheme> attribute of
C<'digest'> and an C<id> attribute containing a Base64-encoded
SHA1 digest of the string C<"$username:$password">.  The
Perl modules Digest and MIME::Base64 may be used to create
such ACL ID values as follows:

  use Digest qw();
  use MIME::Base64 qw();

  my $ctx = Digest->new('SHA-1')->add("$username:$password");
  my $digest = MIME::Base64::encode($ctx->digest());

Note that using the C<b64digest()> method of the Digest module
will not result in digest strings with the "=" suffix characters
required by ZooKeeper.

=head2 Logging

As of ZooKeeper version 3.1.1, logging in the C client code is
implemented with a single, shared file handle to which all
of the internal POSIX threads write log messages; by default,
this file handle is attached to STDERR.

Moreover, this file handle is shared by all active ZooKeeper
connections (each of which has its own private IO and completion
threads; see L</Internal POSIX Threads> above).

Net::ZooKeeper therefore does not provide per-connection handle
attributes related to logging.  The global function
C<Net::ZooKeeper::set_log_level()> may be used to set the current
log level.  See C<:log_levels> for a list of the available log
levels.  The default log level is C<ZOO_LOG_LEVEL_OFF>.

To capture ZooKeeper log messages to a file instead of STDERR,
redirect STDERR to a new file handle in the normal Perl manner:

  open(OLDERR, '>&', fileno(STDERR)) or
    die("unable to dup STDERR: $!");
  open(STDERR, '>', $log_file) or
    die("unable to redirect STDERR: $!");  

=head2 Connection Order

ZooKeeper clusters are typically made up of an odd number of
ZooKeeper servers.  When connecting to such a cluster, the
C<new()> method should be passed a comma-separated list of
the hostnames and ports for each of the servers in the cluster,
e.g., C<'host1:7000,host2:7000,host2:7100'>.

The default behaviour of the ZooKeeper client code is to
reorder this list randomly before making any connections.
A connection is then made to the first server in the reordered
list.  If that connection fails, the IO thread will
automatically attempt to reconnect to the cluster, this time
to the next server in the list; when the last server in the list
is reached, the IO thread will continue again with the first
server.

For certain purposes it may be necessary for ZooKeeper clients
to know the exact order in which the IO thread will attempt to
connect to the servers of a cluster.  To do so, call
C<Net::ZooKeeper::set_deterministic_conn_order(1)>.  Note,
however, that this will affect all Net::ZooKeeper object
handles created by the current process.

=head1 ATTRIBUTES

=head2 Net::ZooKeeper

The Net::ZooKeeper class provides the main interface to the
ZooKeeper client API.  The following attributes are available
for each Net::ZooKeeper handle object and are specific to
that handle and the method calls invoked on it.  As with DBI
handle objects, attributes may be read and written through
a hash interface, e.g.:

  print sprintf("Session timeout is %.2f seconds.\n",
    $zkh->{session_timeout} / 1000);

  $zkh->{watch_timeout} = 10000;

=over 4

=item hosts

The comma-separated list of ZooKeeper server hostnames and ports
as passed to the C<new()> method.  Note that by default the
ZooKeeper C client code will reorder this list before attempting
to connect for the first time; see L</Connection Order> for details.

This attribute is B<read-only> and may not be modified.

=item session_timeout

The session timeout value, in milliseconds, as set by the
ZooKeeper server after connection.  This value may not be
exactly the same as what was requested in the C<'session_timeout'>
option of the C<new()> method; the server will adjust the
requested timeout value so that it is within a certain range
of the server's C<tickTime> setting.  See the ZooKeeper
documentation for details.

Because the actual connection to the ZooKeeper server is
not made during the C<new()> method call but shortly
thereafter by the IO thread, note that this value may not
be initialized to its final value until at least one
other method which requires communication with the server
(such as C<exists()>) has succeeded.

This attribute is B<read-only> and may not be modified.

=item session_id

The client's session ID value as set by the ZooKeeper server
after connection.  This is a binary data string which may
be passed to subsequent C<new()> calls as the value of
the C<'session_id'> option, if the user wishes to attempt to
continue a session after a failure.  Note that the server
may not honour such an attempt.

Because the actual connection to the ZooKeeper server is
not made during the C<new()> method call but shortly
thereafter by the IO thread, note that this value may not
be initialized to its final value until at least one
other method which requires communication with the server
(such as C<exists()>) has succeeded.

This attribute is B<read-only> and may not be modified.

=item data_read_len

The maximum length of node data that will be returned to
the caller by the C<get()> method.  If a node's data exceeds
this length, the returned value will be shorter than the
actual node data as stored in the ZooKeeper cluster.

The default maximum length of the node data returned by
C<get()> is 1023 bytes.  This may be changed by setting
the C<data_read_len> attribute to a different value.

Passing a value for the C<'data_read_len'> option when calling
the C<get()> method will temporarily override the per-handle
maximum.

=item path_read_len

The maximum length of a newly created node's path that will
be returned to the caller by the C<create()> method.  If the path
of the newly created node exceeds this length, the returned
value will be shorter than the actual path of the node as stored
in the ZooKeeper cluster.

The default maximum length of the node path returned by
C<create()> is 1023 bytes.  This may be changed by setting
the C<path_read_len> attribute to a different value.

Passing a value for the C<'path_read_len'> option when calling
the C<create()> method will temporarily override the current
value of this attribute.

=item watch_timeout

The C<timeout> attribute value, in milliseconds, inherited by
all watch objects (of class Net::ZooKeeper::Watch) created by
calls to the C<watch()> method.  When a watch object's
C<wait()> method is invoked without a C<'timeout'> option,
it waits for an event notification from the ZooKeeper cluster
for no longer than the timeout period specified by the value of
the watch object's C<timeout> attribute.

The default C<timeout> attribute value for all watch objects
created by the C<watch()> method is 1 minute (60000
milliseconds).  This may be changed for a particular handle
object by setting this attribute to a different value; afterwards,
the new value will be inherited by any watch objects created
by the handle object's C<watch()> method.  Previously
created watch objects will not be affected.

Passing a value for the C<'timeout'> option when calling
the C<watch()> method will temporarily override the current
value of this attribute and cause the newly created watch object
to inherit a different value.

See also the C<watch()> method, and the C<timeout> attribute
and C<wait()> method of the Net::ZooKeeper::Watch class.

=item pending_watches

The number of internal ZooKeeper watches created for this handle
object that are still awaiting an event notification from the
ZooKeeper cluster.

Note that this number may be different than the number of
extant watch objects created by the handle object's C<watch()>
method, not only because some event notifications may have
occurred, but also if any watch objects have been reassigned
by reusing them in more than one call to any of the C<exists()>,
C<get_children()>, or C<get()> methods.

This attribute is B<read-only> and may not be modified.

=back

=head2 Net::ZooKeeper::Stat

The Net::ZooKeeper::Stat class provides a hash interface to
the individual pieces of information which together compose the
state of a given ZooKeeper node.  Net::ZooKeeper::Stat objects
are created by calling the C<stat()> method on a Net::ZooKeeper
handle object, and may then be passed to any methods which accept
a C<'stat'> option value, such as C<exists()>.

Net::ZooKeeper::Stat objects may be reused multiple times.
If the Net::ZooKeeper method to which the stat object is
passed succeeds, then the stat object is updated with the newly
retrieved node state information, and any state information
previously stored in the stat object is overwritten.

All of the attributes of stat objects are B<read-only>.

=over 4

=item ctime

The creation time of the node in milliseconds since the epoch.

=item mtime

The time of the last modification of the node's data in
milliseconds since the epoch.

=item data_len

The length of the node's data in bytes.

=item num_children

The number of child nodes beneath of the current node.

=item ephemeral_owner

If the node was created with the C<ZOO_EPHEMERAL> flag,
this attribute holds the session ID of the ZooKeeper client
which created the node.  If the node was not created with
the C<ZOO_EPHEMERAL> flag, this attribute is set to zero.

=item version

The number of revisions of the node's data.  The ZooKeeper
cluster will increment this version number whenever the
node's data is changed.  When the node is first created this
version number is initialized to zero.

=item acl_version

The number of revisions of the node's ACL.  The ZooKeeper
cluster will increment this version number whenever the
node's ACL is changed.  When the node is first created this
version number is initialized to zero.

=item children_version

The number of revisions of the node's list of child nodes.
The ZooKeeper cluster will increment this version number
whenever the list of child nodes is changed.  When the node
is first created this version number is initialized to zero.

=item czxid

The ZooKeeper transaction ID (ZXID) of the transaction which
created the node.

=item mzxid

The ZooKeeper transaction ID (ZXID) of the transaction which
last modified the node's data.  This is initially set to
the same transaction ID as the C<czxid> attribute by the
C<create()> method.

=item children_zxid

The ZooKeeper transaction ID (ZXID) of the transaction which
last modified the node's list of child nodes.  This is
initially set to the same transaction ID as the C<czxid>
attribute by the C<create()> method.

=back

=head2 Net::ZooKeeper::Watch

The Net::ZooKeeper::Watch class provides a hash interface
to the data returned by event notifications from the ZooKeeper
cluster.  Net::ZooKeeper::Watch objects are created by calling
the C<watch()> method on a Net::ZooKeeper handle object, and
may then be passed to any methods which accept a C<'watch'>
option value, such as C<exists()>.

Net::ZooKeeper::Watch objects may be reused multiple times.
Regardless of whether the Net::ZooKeeper method to which the
watch object is passed succeeds, the watch object will be
updated to receive an event notification exclusively for the
node referenced in that method call.  In the case of an error,
however, the watch object may never receive any event
notification.

=over 4

=item timeout

The default timeout value, in milliseconds, for all
invocations of the C<wait()> method made on the watch object.
When the C<wait()> method is invoked without a
C<'timeout'> option value, it waits for an
event notification from the ZooKeeper cluster for no longer
than the timeout period specified by this attribute.
This default timeout period may be altered by setting this
attribute to a different value.

Passing a value for the C<'timeout'> option when calling
the C<wait()> method will temporarily override the current
value of this attribute and cause the C<wait()> method to
use a different timeout period.

When a Net::ZooKeeper handle object's C<watch()> method is
invoked without a C<'timeout'> option, it returns a newly
created watch object whose C<timeout> attribute value
is initialized to the current value of the handle object's
C<watch_timeout> attribute.  When the C<watch()> method is invoked
with a C<'timeout'> option, the new watch object's C<timeout>
attribute value is initialized to the value specified by
the C<'timeout'> option.

See also the C<wait()> method, and the C<watch_timeout> attribute
and C<watch()> method of the Net::ZooKeeper class.

=item event

The type of event which triggered the notification, such
as C<ZOO_CHANGED_EVENT> if the node's data was changed.
See C<:events> for a list of the possible event types.
If zero, no event notification has occurred yet.

Note that the events which will trigger a notification
will depend on the Net::ZooKeeper method to which
the watch object was passed.  Watches set through the
C<exists()> and C<get()> methods will report events relating
to the node's data, while watches set through the
C<get_children()> method will report events relating to the
creation or deletion of child nodes of the watched node.

This attribute is B<read-only> and may not be modified.

=item state

The state of the Net::ZooKeeper connection at the time of
the event notification.  See C<:states> for a list of
the possible connection states.  If zero, no event
notification has occurred yet.

This attribute is B<read-only> and may not be modified.

=back

=head1 METHODS

=head2 Net::ZooKeeper

The following methods are defined for the Net::ZooKeeper class.

=over 4

=item new()

  $zkh = Net::ZooKeeper->new('host1:7000,host2:7000');
  $zkh = Net::ZooKeeper->new('host1:7000,host2:7000',
                             'session_timeout' => $session_timeout,
                             'session_id' => $session_id);

Creates a new Net::ZooKeeper handle object and attempts to
connect to the one of the servers of the given ZooKeeper
cluster.  As described in the L</Internal POSIX Threads> and
L</Connection Order> sections, the ZooKeeper client code will
create an IO thread which maintains the connection with a
regular "heartbeat" request.  In the event of a connection error
the IO thread will also attempt to reconnect to another one of
the servers using the same session ID.  In general, these actions
should be invisible to the user, although Net::ZooKeeper methods
may return transient errors while the IO thread
reconnects with another server.

To disconnect, undefine the Net::ZooKeeper handle object
or call the C<DESTROY()> method.  (After calling C<DESTROY()>
the handle object can not be reused.)

The ZooKeeper client code will send a "heartbeat" message
if a third of the session timeout period has elapsed without
any communication with the ZooKeeper server.  A specific
session timeout period may be requested when creating a
Net::ZooKeeper handle object by supplying a value, in
milliseconds, for the C<'session_timeout'> option.  The
ZooKeeper server adjust the requested timeout value so that
it is within a certain range of the server's C<tickTime> setting;
the actual session timeout value will be available as the
value of the handle's C<session_timeout> attribute after at
least one method call has succeeded.  See the C<session_timeout>
attribute for more information.

If no C<'session_timeout'> option is provided, the default
value of 10 seconds (10000 milliseconds) will be used in the
initial connection request; again, the actual timeout period to
which the server agrees will be available subsequently as the
value of the C<session_timeout> attribute.

Upon successful connection (i.e., after the success of a method
which requires communication with the server), the C<session_id>
attribute will hold a short binary string which represents the
client's session ID as set by the server.  All ephemeral nodes
created by the session are identified by this ID in the
C<ephemeral_owner> attribute of any Net::ZooKeeper::Stat objects
used to query their state.

The ZooKeeper client code will use this session ID internally
whenever it tries to reconnect to another server in the ZooKeeper
cluster after detecting a failed connection.  If it successfully
reconnects with the same session ID, the session will continue
and ephemeral nodes belonging to it will not be deleted.

However, if the server determines that the session has timed
out (for example because no "heartbeat" requests have been
received within the agreed-upon session timeout period), the
session will be terminated by the cluster and all ephemeral nodes
owned by the current session automatically deleted.

On occasion the ZooKeeper client code may not be able to quickly
reconnect to a live server and the caller may want to destroy
the existing Net::ZooKeeper handle object and attempt a
fresh connection using the same session ID as before with a
new Net::ZooKeeper object.  To do so, save the C<session_id>
attribute value before undefining the old handle object
and then pass that binary string as the value of the
C<'session_id'> option to the C<new()> method when creating the
next handle object.  After the successful completion of a
method which requires communication with the server, if the
new handle object's C<session_id> attribute value matches the
old session ID then the session has been successfully maintained;
otherwise, the old session was expired by the cluster.

=item get_error()

  $code = $zkh->get_error();

Returns the ZooKeeper error code, if any, from the most
recent Net::ZooKeeper method invocation.  The returned value
will be zero (equivalent to C<ZOK>) if no error occurred,
otherwise non-zero.  Non-zero values may be compared to
the error code names exported by the C<:errors> tagset.

See L</Error Handling> for more details.

=item add_auth()

  $zkh->add_auth('digest', "$username:$password");

The C<add_auth()> method may be used to add authentication
credentials to a session.  Once a credential has been added for
the current session, there is no way to disable it.

When using the digest authentication scheme, note that the
username and password are transmitted in cleartext
to the ZooKeeper cluster.

See L</Access Control> for additional details.

=item create()

  $path = $zkh->create($req_path, $data);
  $path = $zkh->create($req_path, $data,
                       'flags' => (ZOO_EPHEMERAL | ZOO_SEQUENCE),
                       'acl' => ZOO_OPEN_ACL_UNSAFE,
                       'path_read_len' => 100);

Requests that a node be created in the ZooKeeper cluster's
hierarchy with the given path and data.  Upon success,
the returns the node's path, otherwise undef.

The path returned by a successful C<create()> method call
may not be the new node's full path as it appears in the
ZooKeeper hierarchy, depending on the length of the actual
path and the value of the handle object's C<path_read_len>
attribute.  If the length of the actual path exceeds the
current value of the C<path_read_len> attribute, the path
returned by the C<create()> method will be truncated; note
that the node's path in the ZooKeeper hierarchy is not
affected by this truncation.

Specifying a value for the C<'path_read_len'> option will
temporarily override the value of the C<path_read_len>
attribute for the duration of the C<create()> method.

The flag values available for use with the C<'flags'> option
are C<ZOO_EPHEMERAL> and C<ZOO_SEQUENCE>; both are
included in the C<:flags> tagset.  The flags should be
combined with the bitwise OR operator if more than one
is required.

The C<ZOO_EPHEMERAL> flag causes the node to be marked as
ephemeral, meaning it will be automatically deleted if it
still exists when the client's session ends.  The
C<ZOO_SEQUENCE> flag causes a unique integer to be appended
to the node's final path component.  See the ZooKeeper
documentation for additional advice on how to use these flags.

When creating a node it may be important to define an ACL
for it; to do this, pass a reference to an ACL array (as
described in L</Access Control>) using the C<'acl'> option.
See also the C<:acl_perms> and C<:acls> tagsets for lists
of the available ACL permission flags and pre-defined ACLs.

=item delete()

  $ret = $zkh->delete($path);
  $ret = $zkh->delete($path, 'version' => $version);

Requests that a node be deleted from the ZooKeeper hierarchy.
Returns true upon success, false otherwise.  

If a value for the C<'version'> option is supplied, the node
will only be deleted if its version number matches the given
value.  See the C<version> attribute of the Net::ZooKeeper::Stat
class for details on node version numbering.

=item exists()

  $ret = $zkh->exists($path);
  $ret = $zkh->exists($path, 'stat' => $stat, 'watch' => $watch);

Tests whether a given node exists.  Returns true if the node
exists, otherwise false.  When the C<exists()> method is successful
but the node does not exist, it returns false, and C<get_error()>
will return C<ZNONODE> until another method is called on the
handle object.

The C<'stat'> option may be used to request that a
Net::ZooKeeper::Stat object be updated with the node's
current state information.  The stat object will only be
updated if the node exists and the C<exists()> method
succeeds.  The stat object must first have been created
using the C<stat()> method.

The C<'watch'> option may be used to request that a
Net::ZooKeeper::Watch object be assigned to receive
notification of an event which alters the node's data.
The watch object must first have been created using the
C<watch()> method.  If the watch object was previously
assigned to receive notifications for another node, it
will be reassigned even if the C<exists()> method fails.

=item get_children()

  @child_names  = $zkh->get_children($path);
  $num_children = $zkh->get_children($path, 'watch' => $watch);

Queries the names or number of the child nodes stored beneath
a given node in the ZooKeeper hierarchy.  In a list context,
returns a list of the child nodes' names upon success, otherwise
an empty list.  When the C<get_children()> method is successful
but there are no child nodes, it returns an empty list, and
C<get_error()> will return C<ZOK> until another method is called
on the handle object.

In a scalar context, C<get_children()> returns the number
of child nodes upon success, otherwise undef.

The names of the child nodes are simply the final component
of the nodes' paths, i.e., the portion of their path which
follows the path of the given parent node, excluding the
"/" delimiter.

The C<'watch'> option may be used to request that a
Net::ZooKeeper::Watch object be assigned to receive
notification of an event which alters the node's list of
child nodes.  The watch object must first have been created
using the C<watch()> method.  If the watch object was
previously assigned to receive notifications for another node,
it will be reassigned even if the C<get_children()> method fails.

=item get()

  $data = $zkh->get($path);
  $data = $zkh->get($path, 'data_read_len' => 100,
                    'stat' => $stat, 'watch' => $watch);

Queries the data stored in a given node.  Returns the
data as a string upon success, otherwise undef.  Note
that the data may contain nulls if the node's data is
not a text string.

If the length of the node's data exceeds the current value
of the handle object's C<data_read_len> attribute, the
string returned by the C<get()> method will be truncated;
note that the node's data in the ZooKeeper cluster is not
affected by this truncation.

Specifying a value for the C<'data_read_len'> option will
temporarily override the value of the C<data_read_len>
attribute for the duration of the C<get()> method.

The C<'stat'> option may be used to request that a
Net::ZooKeeper::Stat object be updated with the node's
current state information.  The stat object will only be
updated if the C<get()> method succeeds.  The stat object
must first have been created using the C<stat()> method.

The C<'watch'> option may be used to request that a
Net::ZooKeeper::Watch object be assigned to receive
notification of an event which alters the node's data.
The watch object must first have been created using the
C<watch()> method.  If the watch object was previously
assigned to receive notifications for another node, it
will be reassigned even if the C<get()> method fails.

=item set()

  $ret = $zkh->set($path, $data);
  $ret = $zkh->set($path, $data, 'version' => $version,
                   'stat' => $stat);

Requests that a node's data be updated in the ZooKeeper
hierarchy.  Returns true upon success, false otherwise.  

If a value for the C<'version'> option is supplied, the node's
data will only be updated if its version number matches the
given value.  See the C<version> attribute of the
Net::ZooKeeper::Stat class for details on node version numbering.

The C<'stat'> option may be used to request that a
Net::ZooKeeper::Stat object be updated with the node's
current state information.  The stat object will only be
updated if the C<set()> method succeeds.  The stat object
must first have been created using the C<stat()> method.

=item get_acl()

  @acl = $zkh->get_acl($path);
  $num_acl_entries = $zkh->get_acl($path, 'stat' => $stat);

Queries the ACL associated with a node in the ZooKeeper
hierarchy, if any.  In a list context, returns an array with
the node's ACL entries upon success, otherwise
an empty list.  When the C<get_acl()> method is successful
but there are no ACL entries, it returns an empty list, and
C<get_error()> will return C<ZOK> until another method is called
on the handle object.

The elements of the returned array are hashes, each of which
represents one ACL entry.  Each hash contains C<perms>,
C<scheme>, and C<id> elements.  See the L</Access Control>
section for additional details, and the
C<:acl_perms> and C<:acls> tagsets for lists of the
available ACL permission flags and pre-defined ACLs.

In a scalar context, C<get_acl()> returns the number
of ACL entries upon success, otherwise undef.

The C<'stat'> option may be used to request that a
Net::ZooKeeper::Stat object be updated with the node's
current state information.  The stat object will only be
updated if the C<get_acl()> method succeeds.  The stat object
must first have been created using the C<stat()> method.

=item set_acl()

  $acl = [{
    'perms' => (ZOO_PERM_READ | ZOO_PERM_WRITE),
    'scheme' => 'digest',
    'id' => "$username:$digest"
  }];
  $ret = $zkh->set_acl($path, $acl);
  $ret = $zkh->set_acl($path, ZOO_OPEN_ACL_UNSAFE,
                       'version' => $version);

Requests that a node's ACL be updated in the ZooKeeper
hierarchy.  Returns true upon success, false otherwise.

The ACL should be passed as a reference to an array of
hashes, where each hash represents one ACL entry.  Each
hash should contain  C<perms>, C<scheme>, and C<id> elements
as described in the L</Access Control> section.
See also the C<:acl_perms> and C<:acls> tagsets for lists
of the available ACL permission flags and pre-defined ACLs.

If a value for the C<'version'> option is supplied, the node's
ACL will only be updated if its version number matches the
given value.  See the C<version> attribute of the
Net::ZooKeeper::Stat class for details on node version numbering.

=item stat()

  $stat = $zkh->stat();

Creates a new Net::ZooKeeper::Stat object which may be used
with the C<'stat'> option of the C<exists()>, C<get()>,
C<set()>, and C<get_acl()> methods.  When the stat object
is passed to any of these methods, upon success its attribute
values are updated to reflect the current state of the
node specified in the method call.  The stat object is not
updated if the method call does not succeed.

=item watch()

  $watch = $zkh->watch();
  $watch = $zkh->watch('timeout' => $timeout);

Creates a new Net::ZooKeeper::Watch object which may be
used to wait for event notifications from the ZooKeeper
cluster.  Each time the watch object is passed to any
of the C<exists()>, C<get_children()>, or C<get()> methods,
its attribute values are immediately reset to zero, and will
later be updated upon receipt of an appropriate event
notification for the node specified in the method call.

The specific types of events which cause notifications to be
sent by the ZooKeeper cluster depend on the method call used.
After use with the C<exists()> and C<get()> methods, the
watch object will be set to receive an event notification
caused by a modification of the node's data or the node itself
(e.g., deletion of the node).  After use with the
C<get_children()> method, the watch object will be set to
receive an event notification caused by a modification
of the node's list of child nodes.

Watch objects receive at most one event notification after
their assignment to a node by one of the C<exists()>,
C<get_children()>, or C<get()> methods.  Note that in the
case of an error, the watch object may never receive any
event notification.  However, when the parent Net::ZooKeeper
handle object experiences a connection error, the ZooKeeper
client code will notify all pending watches with an event of
type C<ZOO_SESSION_EVENT>.  See C<wait()> for more information
regarding the watch object's attribute values after a
connection error.

A watch object may be reused with another C<exists()>,
C<get_children()>, or C<get()> method call at any time,
in which case the watch object's attribute values
are reset to zero and the watch object will no longer be updated
by any event notification relevant to the previous method call.

When the C<watch()> method is invoked without a C<'timeout'>
option, it returns a newly created watch object whose C<timeout>
attribute value is initialized to the current value of the
Net::ZooKeeper handle object's C<watch_timeout> attribute.
Otherwise, when the C<watch()> method is invoked with a
C<'timeout'> option, the new watch object's C<timeout> attribute
value is initialized to the value specified by the
C<'timeout'> option.

See also the C<watch_timeout> attribute, and the C<timeout>
attribute and C<wait()> method of the Net::ZooKeeper::Watch
class.

=back

=head2 Net::ZooKeeper::Stat

No methods are defined for the Net::ZooKeeper::Stat class.

=head2 Net::ZooKeeper::Watch

Only one method is defined for the Net::ZooKeeper::Watch class.

=over 4

=item wait()

  $ret = $watch->wait();
  $ret = $watch->wait('timeout' => $timeout);

Waits for an event notification from the ZooKeeper cluster
for the node most recently associated with the watch object.
Nodes are associated with a watch object by passing the
watch object as the value of a C<'watch'> option to a
Net::ZooKeeper method; methods which accept a C<'watch'> option
are C<exists()>, C<get_children()>, and C<get()>.

When the C<wait()> method is invoked with a C<'timeout'>
option, it waits for no more than the number of milliseconds
specified by the C<'timeout'> option.
Otherwise, when the C<wait()> method is invoked without a
C<'timeout'> option, it waits for no more than the timeout
period specified by the value of the watch object's C<timeout>
attribute.

The C<wait()> method returns true if an event notification
was received, otherwise false.  When C<wait()> returns true,
the C<event> and C<state> attributes of the watch object
will be updated with the event's type and the current
connection state.

When the parent Net::ZooKeeper handle object experiences a
connection error, the ZooKeeper client code will notify all
pending watches with an event of type C<ZOO_SESSION_EVENT>.
In this case, the C<state> attribute will report the current
state of the connection to the ZooKeeper cluster.

See also the C<timeout> attribute, and the C<watch()> method
and C<watch_timeout> attribute of the Net::ZooKeeper class.

=back

=head1 FUNCTIONS

The following functions have global scope and affect all
Net::ZooKeeper handle objects.

=over 4

=item set_log_level()

  Net::ZooKeeper::set_log_level($level);

The C<Net::ZooKeeper::set_log_level()> function may be called to
alter the number and type of messages written to the current log
file handle (if any).  The default value is C<ZOO_LOG_LEVEL_OFF>
which disables all logging.

See the L</Logging> section for more details and C<:log_levels>
for a list of the available log levels.

=item set_deterministic_conn_order()

  Net::ZooKeeper::set_deterministic_conn_order(1);

The C<Net::ZooKeeper::set_deterministic_conn_order()> function
may be called to indicate whether or not the list of ZooKeeper
servers passed to the C<new()> method should be randomly permuted.
If set to a true value, the list of servers will not be altered.
The default false value indicates the list of servers will
be randomly reordered prior to connection.

See the L</Connection Order> section for more details.

=back

=head1 EXPORTS

Nothing is exported by default.  Various tagsets exist which
group the tags available for export into different categories:

=over 4

=item :errors

ZooKeeper error codes.  These may be compared to the values
returned by the C<get_error()> method.

=item :node_flags

The ZooKeeper node flags C<ZOO_EPHEMERAL> and C<ZOO_SEQUENCE>,
which may be passed in the C<'flags'> option to the C<create()>
method.  When more than node flag is required they
should be combined using the bitwise OR operator.

=item :acl_perms

The ZooKeeper ACL permission flags which may be used in
the value of the C<perms> attribute of an ACL entry hash.
When more than one ACL permission flag is required they
should be combined using the bitwise OR operator.

The available ACL permission flags are C<ZOO_PERM_READ>,
C<ZOO_PERM_WRITE>, C<ZOO_PERM_CREATE>, C<ZOO_PERM_DELETE>,
and C<ZOO_PERM_ADMIN>.  For convenience, C<ZOO_PERM_ALL> is
defined as the bitwise OR of all of these flags.

=item :acls

Common ZooKeeper ACLs which may be useful.  C<ZOO_OPEN_ACL_UNSAFE>
specifies a node which is entirely open to all users with no
restrictions at all.  C<ZOO_READ_ACL_UNSAFE> specifies
a node which is readable by all users; permissions for other actions
are not defined in this ACL.  C<ZOO_CREATOR_ALL_ACL> specifies a node
for which all actions require the same authentication credentials as
held by the session which created the node; this implies that a
session should authenticate with an appropriate scheme before
creating a node with this ACL.

=item :events

The ZooKeeper event types which are returned in value of
the C<event> attribute a Net::ZooKeeper::Watch object after
an event occurs on a watched node.

=item :states

The ZooKeeper connection states which are returned in value of
the C<state> attribute of a Net::ZooKeeper::Watch object after
an event occurs on a watched node.

=item :log_levels

The ZooKeeper log levels which may be passed to the
C<Net::ZooKeeper::set_log_level()> function.  The available
log levels are, from least to most verbose, C<ZOO_LOG_LEVEL_OFF>
(the default), C<ZOO_LOG_LEVEL_ERROR>, C<ZOO_LOG_LEVEL_WARN>,
C<ZOO_LOG_LEVEL_INFO>, and C<ZOO_LOG_LEVEL_DEBUG>.

=item :all

Everything from all of the above tagsets.

=back

=head1 SEE ALSO

The Apache ZooKeeper project's home page at
L<http://hadoop.apache.org/zookeeper/> provides a wealth of detail
on how to develop applications using ZooKeeper.

=head1 AUTHOR

Chris Darroch, E<lt>chrisd@apache.orgE<gt>

=head1 COPYRIGHT AND LICENSE

Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

=cut

