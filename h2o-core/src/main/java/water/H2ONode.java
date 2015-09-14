package water;

import water.RPC.RPCCall;
import water.UDP.udp;
import water.nbhm.NonBlockingHashMap;
import water.nbhm.NonBlockingHashMapLong;
import water.util.Log;
import water.util.UnsafeUtils;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A <code>Node</code> in an <code>H2O</code> Cloud.
 * Basically a worker-bee with CPUs, Memory and Disk.
 * One of this is the self-Node, but the rest are remote Nodes.
 *
 * @author <a href="mailto:cliffc@h2o.ai"></a>
 * @version 1.0
 */

public class H2ONode extends Iced<H2ONode> implements Comparable {
  short _unique_idx; // Dense integer index, skipping 0.  NOT cloud-wide unique.
  boolean _announcedLostContact;  // True if heartbeat published a no-contact msg
  public long _last_heard_from; // Time in msec since we last heard from this Node
  public volatile HeartBeat _heartbeat;  // My health info.  Changes 1/sec.
  public int _tcp_readers;               // Count of started TCP reader threads

  // A JVM is uniquely named by machine IP address and port#
  public final H2Okey _key;
  public static final class H2Okey extends InetSocketAddress implements Comparable {
    final int _ipv4;     // cheapo ipv4 address
    H2Okey(InetAddress inet, int port) {
      super(inet,port);
      byte[] b = inet.getAddress();
      _ipv4 = ((b[0]&0xFF)<<0)+((b[1]&0xFF)<<8)+((b[2]&0xFF)<<16)+((b[3]&0xFF)<<24);
    }
    public int htm_port() { return getPort()-1; }
    public int udp_port() { return getPort()  ; }
    @Override public String toString() { return getAddress()+":"+htm_port(); }
    public String getIpPortString() {
      return getAddress().getHostAddress() + ":" + htm_port();
    }
    AutoBuffer write( AutoBuffer ab ) {
      return ab.put4(_ipv4).put2((char)udp_port());
    }
    static H2Okey read( AutoBuffer ab ) {
      try { 
        InetAddress inet = InetAddress.getByAddress(ab.getA1(4));
        int port = ab.get2();
        return new H2Okey(inet,port);
      } catch( UnknownHostException e ) { throw Log.throwErr(e); }
    }
    // Canonical ordering based on inet & port
    @Override public int compareTo( Object x ) {
      if( x == null ) return -1;   // Always before null
      if( x == this ) return 0;
      H2Okey key = (H2Okey)x;
      // Must be unsigned long-math, or overflow will make a broken sort
      long res = (_ipv4&0xFFFFFFFFL) - (key._ipv4&0xFFFFFFFFL);
      if( res != 0 ) return res < 0 ? -1 : 1;
      return udp_port() - key.udp_port();
    }
  }

  public String getIpPortString() {
    return _key.getIpPortString();
  }

  public final int ip4() { return _key._ipv4; }

  // These are INTERN'd upon construction, and are uniquely numbered within the
  // same run of a JVM.  If a remote Node goes down, then back up... it will
  // come back with the SAME IP address, and the same unique_idx and history
  // relative to *this* Node.  They can be compared with pointer-equality.  The
  // unique idx is used to know which remote Nodes have cached which Keys, even
  // if the Home#/Replica# change for a Key due to an unrelated change in Cloud
  // membership.  The unique_idx is *per Node*; not all Nodes agree on the same
  // indexes.
  private H2ONode( H2Okey key, short unique_idx ) {
    _key = key;
    _unique_idx = unique_idx;
    _last_heard_from = System.currentTimeMillis();
    _heartbeat = new HeartBeat();
  }

  // ---------------
  // A dense integer index for every unique IP ever seen, since the JVM booted.
  // Used to track "known replicas" per-key across Cloud change-ups.  Just use
  // an array-of-H2ONodes
  static private final NonBlockingHashMap<H2Okey,H2ONode> INTERN = new NonBlockingHashMap<>();
  static private final AtomicInteger UNIQUE = new AtomicInteger(1);
  static H2ONode IDX[] = new H2ONode[1];

  // Create and/or re-use an H2ONode.  Each gets a unique dense index, and is
  // *interned*: there is only one per InetAddress.
  static private H2ONode intern( H2Okey key ) {
    H2ONode h2o = INTERN.get(key);
    if( h2o != null ) return h2o;
    final int idx = UNIQUE.getAndIncrement();
    assert idx < Short.MAX_VALUE;
    h2o = new H2ONode(key,(short)idx);
    H2ONode old = INTERN.putIfAbsent(key,h2o);
    if( old != null ) return old;
    synchronized(H2O.class) {
      while( idx >= IDX.length )
        IDX = Arrays.copyOf(IDX,IDX.length<<1);
      IDX[idx] = h2o;
    }
    return h2o;
  }
  public static H2ONode intern( InetAddress ip, int port ) { return intern(new H2Okey(ip,port)); }

  public static H2ONode intern( byte[] bs, int off ) {
    byte[] b = new byte[4];
    UnsafeUtils.set4(b, 0, UnsafeUtils.get4(bs, off));
    int port = UnsafeUtils.get2(bs,off+4)&0xFFFF;
    try { return intern(InetAddress.getByAddress(b),port); } 
    catch( UnknownHostException e ) { throw Log.throwErr(e); }
  }

  static H2ONode intern( int ip, int port ) {
    byte[] b = new byte[4];
    b[0] = (byte)(ip>> 0);
    b[1] = (byte)(ip>> 8);
    b[2] = (byte)(ip>>16);
    b[3] = (byte)(ip>>24);
    try { return intern(InetAddress.getByAddress(b),port); } 
    catch( UnknownHostException e ) { throw Log.throwErr(e); }
  }

  // Get a nice Node Name for this Node in the Cloud.  Basically it's the
  // InetAddress we use to communicate to this Node.
  public static H2ONode self(InetAddress local) {
    assert H2O.H2O_PORT != 0;
    try {
      // Figure out which interface matches our IP address
      List<NetworkInterface> matchingIfs = new ArrayList<>();
      Enumeration<NetworkInterface> netIfs = NetworkInterface.getNetworkInterfaces();
      while( netIfs.hasMoreElements() ) {
        NetworkInterface netIf = netIfs.nextElement();
        Enumeration<InetAddress> addrs = netIf.getInetAddresses();
        while( addrs.hasMoreElements() ) {
          InetAddress addr = addrs.nextElement();
          if( addr.equals(local) ) {
            matchingIfs.add(netIf);
            break;
          }
        }
      }
      switch( matchingIfs.size() ) {
      case 0: H2O.CLOUD_MULTICAST_IF = null; break;
      case 1: H2O.CLOUD_MULTICAST_IF = matchingIfs.get(0); break;
      default:
        String msg = "Found multiple network interfaces for ip address " + local;
        for( NetworkInterface ni : matchingIfs ) {
          msg +="\n\t" + ni;
        }
        msg +="\nUsing " + matchingIfs.get(0) + " for UDP broadcast";
        Log.warn(msg);
        H2O.CLOUD_MULTICAST_IF = matchingIfs.get(0);
      }
    } catch( SocketException e ) {
      throw Log.throwErr(e);
    }

    // Selected multicast interface must support multicast, and be up and running!
    try {
      if( H2O.CLOUD_MULTICAST_IF != null && !H2O.CLOUD_MULTICAST_IF.supportsMulticast() ) {
        Log.info("Selected H2O.CLOUD_MULTICAST_IF: "+H2O.CLOUD_MULTICAST_IF+ " doesn't support multicast");
//        H2O.CLOUD_MULTICAST_IF = null;
      } 
      if( H2O.CLOUD_MULTICAST_IF != null && !H2O.CLOUD_MULTICAST_IF.isUp() ) {
        throw new RuntimeException("Selected H2O.CLOUD_MULTICAST_IF: "+H2O.CLOUD_MULTICAST_IF+ " is not up and running");
      }
    } catch( SocketException e ) {
      throw Log.throwErr(e);
    }

    try {
      assert water.init.NetworkInit.CLOUD_DGRAM == null;
      water.init.NetworkInit.CLOUD_DGRAM = DatagramChannel.open();
    } catch( Exception e ) {
      throw Log.throwErr(e);
    }
    return intern(new H2Okey(local,H2O.H2O_PORT));
  }

  // Happy printable string
  @Override public String toString() { return _key.toString (); }
  @Override public int hashCode() { return _key.hashCode(); }
  @Override public boolean equals(Object o) { return _key.equals   (((H2ONode)o)._key); }
  @Override public int compareTo( Object o) { return _key.compareTo(((H2ONode)o)._key); }

  // index of this node in the current cloud... can change at the next cloud.
  public int index() { return H2O.CLOUD.nidx(this); }

  // max memory for this node.
  // no need to ask the (possibly not yet populated) heartbeat if we want to know the local max memory.
  public long get_max_mem() { return this == H2O.SELF ? Runtime.getRuntime().maxMemory() : _heartbeat.get_max_mem(); }

  // ---------------
  // A queue of available TCP sockets
  // re-usable TCP socket opened to this node, or null.
  // This is essentially a BlockingQueue/Stack that allows null.
  private SocketChannel _socks[] = new SocketChannel[2];
  private int _socksAvail=_socks.length;
  // Count of concurrent TCP requests both incoming and outgoing
  static final AtomicInteger TCPS = new AtomicInteger(0);

  private SocketChannel _rawChannel;

  /**
   * Wrapper around raw bytes representing a small message and its priority.
   *
   */
  public static class H2OSmallMessage implements Comparable<H2OSmallMessage> {
    private int _priority;
    final private byte [] _data;

    public H2OSmallMessage(byte[] data, int priority) {
      _data = data;
      _priority = priority;
    }
    @Override
    public int compareTo(H2OSmallMessage o) {
      return o._priority - _priority;
    }

    public void increasePriority() {++_priority;}

    /**
     * Make new H2OSmall message from this byte buffer.
     * Extracts bytes between position and limit, adds 2B size in the beginning and 1B EOM marker at the end.*
     *
     * Currently size must be <= max small message size (Autobuffer.BBP_SML.size()).
     *
     * @param bb
     * @param priority
     * @return
     */
    public static H2OSmallMessage make(ByteBuffer bb, int priority) {
      int sz = bb.limit();
      assert sz == (0xFFFF & sz);
      assert sz <= AutoBuffer.BBP_SML.size();
      byte [] ary = MemoryManager.malloc1(sz+2+1);
      ary[ary.length-1] = (byte)0xef; // eom marker
      ary[0] = (byte)(sz & 0xFF);
      ary[1] = (byte)((sz & 0xFF00) >> 8);
      if(bb.hasArray())
        System.arraycopy(bb.array(),0,ary,2,sz);
      else  for(int i = 0; i < sz; ++i)
          ary[i+2] = bb.get(i);
      assert 0 < ary[2] && ary[2] < udp.UDPS.length; // valid ctrl byte
      assert udp.UDPS[ary[2]]._udp != null:"missing udp " + ary[2];
      assert (0xFF & ary[ary.length-1]) == 0xef;
      assert (((0xFF & ary[0]) | ((0xFF & ary[1]) << 8)) + 3) == ary.length;
      return new H2OSmallMessage(ary,priority);
    }
  }


  private final PriorityBlockingQueue<H2OSmallMessage> _msgQ = new PriorityBlockingQueue<>();

  /**
   * Private thread serving (actually ships the bytes over) small msg Q.
   * Buffers the small messages together and sends the bytes over via TCP channel.
   */
  private class UDP_TCP_SendThread extends Thread {
    private final ByteBuffer _bb;

    public UDP_TCP_SendThread(){
      super("UDP-TCP-SEND-" + H2ONode.this);
      _bb = AutoBuffer.BBP_BIG.make();
    }

    void sendBuffer(){
      int sleep = 0;
      _bb.flip();
      int sz = _bb.limit();
      int retries = 0;
      while (true) {
        _bb.position(0);
        _bb.limit(sz);
        try {
          if (_rawChannel == null || !_rawChannel.isOpen() || !_rawChannel.isConnected()) { // open the channel
            // Must make a fresh socket
            SocketChannel sock = SocketChannel.open();
            sock.socket().setReuseAddress(true);
            sock.socket().setSendBufferSize(AutoBuffer.BBP_BIG.size());
            InetSocketAddress isa = new InetSocketAddress(_key.getAddress(), _key.getPort());
            boolean res = false;
            try{
              res = sock.connect(isa);
            } catch(IOException ioe) {
              if(!Paxos._cloudLocked && retries++ < 300) { // cloud not yet up => other node is most likely starting
                try {Thread.sleep(100);} catch (InterruptedException e) {}
                continue;
              } else throw ioe;
            }
            boolean blocking = true;
            sock.configureBlocking(blocking);
            assert res && !sock.isConnectionPending() && (blocking == sock.isBlocking()) && sock.isConnected() && sock.isOpen();
            _rawChannel = sock;
            _rawChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
            bb.put((byte) 1);
            bb.putChar((char) H2O.H2O_PORT);
            bb.put((byte) 0xef);
            bb.flip();
            while (bb.hasRemaining())
              _rawChannel.write(bb);
          }
          while (_bb.hasRemaining())
            _rawChannel.write(_bb);
          _bb.position(0);
          _bb.limit(_bb.capacity());
          return;
        } catch(IOException ioe) {
          if(!H2O.getShutdownRequested())
            Log.err(ioe);
          if(_rawChannel != null)
            try {_rawChannel.close();} catch (Throwable t) {}
          _rawChannel = null;
          if(!H2O.getShutdownRequested())
            Log.warn("Got IO error when sending raw bytes, sleeping for " + sleep + " ms and retrying");
          sleep = Math.min(5000,(sleep + 1) << 1);
          try {Thread.sleep(sleep);} catch (InterruptedException e) {}
        }
      }
    }

    @Override public void run(){
      try {
        while (true) {
          try {
            H2OSmallMessage m = _msgQ.take();
            while (m != null) {
              if (m._data.length > _bb.capacity())
                H2O.fail("Small message larger than the buffer");
              if (_bb.remaining() < m._data.length)
                sendBuffer();
              _bb.put(m._data);
              m = _msgQ.poll();
            }
            sendBuffer();
          } catch (InterruptedException e) {
          }
        }
      } catch(Throwable t) {
        Log.err(t);
        throw H2O.fail();
      }
    }
  }

  private UDP_TCP_SendThread _sendThread = null;

  /**
   * Send small message to this node.
   * Passes the message on to a private msg q, prioritized by the message priority.
   * MSG queue is served by sender thread, message are continuously extracted, buffered toghether and sent over TCP channel.
   * @param msg
   */
  public void sendMessage(H2OSmallMessage msg) {
    _msgQ.put(msg);
    if(_sendThread == null) synchronized(this) {
      if(_sendThread == null)
        (_sendThread = new UDP_TCP_SendThread()).start();
    }
  }


  SocketChannel getTCPSocket() throws IOException {
    // Under lock, claim an existing open socket if possible
    synchronized(this) {
      // Limit myself to the number of open sockets from node-to-node
      while( _socksAvail == 0 )
        try { wait(1000); } catch( InterruptedException ignored ) { }
      // Claim an open socket
      SocketChannel sock = _socks[--_socksAvail];
      if( sock != null ) {
        if( sock.isOpen() ) return sock; // Return existing socket!
        // Else it's an already-closed socket, lower open TCP count
        assert TCPS.get() > 0;
        TCPS.decrementAndGet();
      }
    }
    // Must make a fresh socket
    SocketChannel sock2 = SocketChannel.open();
    sock2.socket().setReuseAddress(true);
    sock2.socket().setSendBufferSize(AutoBuffer.BBP_BIG.size());
    boolean res = sock2.connect( _key );
    assert res && !sock2.isConnectionPending() && sock2.isBlocking() && sock2.isConnected() && sock2.isOpen();
    ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
    bb.put((byte)2);
    bb.putChar((char)H2O.H2O_PORT);
    bb.put((byte)0xef);
    bb.flip();
    while(bb.hasRemaining())
      sock2.write(bb);
    TCPS.incrementAndGet();     // Cluster-wide counting
    return sock2;
  }
  synchronized void freeTCPSocket( SocketChannel sock ) {
    assert 0 <= _socksAvail && _socksAvail < _socks.length;
    if( sock != null && !sock.isOpen() ) sock = null;
    _socks[_socksAvail++] = sock;
    assert TCPS.get() > 0;
    if( sock == null ) TCPS.decrementAndGet();
    notify();
  }

  // ---------------
  // The *outgoing* client-side calls; pending tasks this Node wants answered.
  private final NonBlockingHashMapLong<RPC> _tasks = new NonBlockingHashMapLong<>();
  void taskPut(int tnum, RPC rpc ) { 
    _tasks.put(tnum,rpc); 
    if( rpc._dt instanceof TaskPutKey ) _tasksPutKey.put(tnum,(TaskPutKey)rpc._dt);
  }
  RPC taskGet(int tnum) { return _tasks.get(tnum); }
  void taskRemove(int tnum) { 
    _tasks.remove(tnum); 
    _tasksPutKey.remove(tnum);
  }
  Collection<RPC> tasks() { return _tasks.values(); }
  int taskSize() { return _tasks.size(); }

  // True if there is a pending PutKey against this Key.  Totally a speed
  // optimization in the case of a large number of pending Gets are flooding
  // the tasks() queue, each needing to scan the tasks queue for pending
  // PutKeys to the same Key.  Legal to always 
  private final NonBlockingHashMapLong<TaskPutKey> _tasksPutKey = new NonBlockingHashMapLong<>();
  TaskPutKey pendingPutKey( Key k ) {
    for( TaskPutKey tpk : _tasksPutKey.values() )
      if( k.equals(tpk._key) )
        return tpk;
    return null;
  }

  // The next unique task# sent *TO* the 'this' Node.
  private final AtomicInteger _created_task_ids = new AtomicInteger(1);
  int nextTaskNum() { return _created_task_ids.getAndIncrement(); }


  // ---------------
  // The Work-In-Progress list.  Each item is a UDP packet's worth of work.
  // When the RPCCall to _computed, then it's Completed work instead
  // work-in-progress.  Completed work can be short-circuit replied-to by
  // resending the RPC._dt back.  Work that we're sure the this Node has seen
  // the reply to can be removed - but we must remember task-completion for all
  // time (because UDP packets can be dup'd and arrive very very late and
  // should not be confused with new work).
  private final NonBlockingHashMapLong<RPC.RPCCall> _work = new NonBlockingHashMapLong<>();

  // We must track even dead/completed tasks for All Time (lest a very very
  // delayed UDP packet look like New Work).  The easy way to do this is leave
  // all work packets/RPCs in the _work HashMap for All Time - but this amounts
  // to a leak.  Instead we "roll up" the eldest completed work items, just
  // remembering their completion status.  Task id's older (smaller) than the
  // _removed_task_ids are both completed, and rolled-up to a single integer.
  private final AtomicInteger _removed_task_ids = new AtomicInteger(0);
  // A Golden Completed Task: it's a shared completed task used to represent
  // all instances of tasks that have been completed and are no longer being
  // tracked separately.
  private final RPC.RPCCall _removed_task = new RPC.RPCCall(null,this,0);

  RPC.RPCCall has_task( int tnum ) {
    if( tnum <= _removed_task_ids.get() ) return _removed_task;
    return _work.get(tnum);
  }

  // Record a task-in-progress, or return the prior RPC if one already exists.
  // The RPC will flip to "_completed" once the work is done.  The RPC._dtask
  // can be repeatedly ACKd back to the caller, and the _dtask is removed once
  // an ACKACK appears - and the RPC itself is removed once all prior RPCs are
  // also ACKACK'd.
  RPC.RPCCall record_task( RPC.RPCCall rpc ) {
    // Task removal (and roll-up) suffers from classic race-condition, which we
    // fix by a classic Dekker's algo; a task# is always in either the _work
    // HashMap, or rolled-up in the _removed_task_ids counter, or both (for
    // short intervals during the handoff).  We can never has a cycle where
    // it's in neither or else a late UDP may attempt to "resurrect" the
    // already completed task.  Hence we must always check the "removed ids"
    // AFTER we insert in the HashMap (we can check before also, but that's a
    // simple optimization and not sufficient for correctness).
    final RPC.RPCCall x = _work.putIfAbsent(rpc._tsknum,rpc);
    if( x != null ) return x;   // Return pre-existing work
    // If this RPC task# is very old, we just return a Golden Completed task.
    // The task is not just completed, but also we have already received
    // verification that the client got the answer.  So this is just a really
    // old attempt to restart a long-completed task.
    if( rpc._tsknum > _removed_task_ids.get() ) return null; // Task is new
    _work.remove(rpc._tsknum); // Bogus insert, need to remove it
    return _removed_task;      // And return a generic Golden Completed object
  }
  // Record the final return value for a DTask.  Should happen only once.
  // Recorded here, so if the client misses our ACK response we can resend the
  // same answer back.
  void record_task_answer( RPC.RPCCall rpcall ) {
//    assert rpcall._started == 0 || rpcall._dt.hasException();
    rpcall._started = System.currentTimeMillis();
    rpcall._retry = RPC.RETRY_MS; // Start the timer on when to resend
//    AckAckTimeOutThread.PENDING.add(rpcall);
  }
  // Stop tracking a remote task, because we got an ACKACK.
  void remove_task_tracking( int task ) {
    RPC.RPCCall rpc = _work.get(task);
    if( rpc == null ) return;   // Already stopped tracking

    // Atomically attempt to remove the 'dt'.  If we win, we are the sole
    // thread running the dt.onAckAck.  Also helps GC: the 'dt' is done (sent
    // to client and we received the ACKACK), but the rpc might need to stick
    // around a long time - and the dt might be big.
    DTask dt = rpc._dt;         // The existing DTask, if any
    if( dt != null && rpc.CAS_DT(dt,null) ) {
      assert rpc._computed : "Still not done #"+task+" "+dt.getClass()+" from "+rpc._client;
      dt.onAckAck();            // One-time call on stop-tracking
    }
    // Roll-up as many done RPCs as we can, into the _removed_task_ids list
    while( true ) {
      int t = _removed_task_ids.get();   // Last already-removed ID
      RPC.RPCCall rpc2 = _work.get(t+1); // RPC of 1st not-removed ID
      if( rpc2 == null || rpc2._dt != null || !_removed_task_ids.compareAndSet(t,t+1) )
        break;                  // Stop when we hit in-progress tasks
      _work.remove(t+1);        // Else we can remove the tracking now
    }
  }

  // Resend ACK's, in case the UDP ACKACK got dropped.  Note that even if the
  // ACK was sent via TCP, the ACKACK might be dropped.  Further: even if we
  // *know* the client got our TCP response, we do not know *when* he'll
  // process it... so we cannot e.g. eagerly do an ACKACK on this side.  We
  // must wait for the real ACKACK - which can drop.  So we *must* resend ACK's
  // occasionally to force a resend of ACKACKs.

  static class AckAckTimeOutThread extends Thread {
    AckAckTimeOutThread() { super("ACKTimeout"); }
    // List of DTasks with results ready (and sent!), and awaiting an ACKACK.
    // Started by main() on a single thread, handle timing-out UDP packets
    @Override public void run() {
      Thread.currentThread().setPriority(Thread.MAX_PRIORITY-1);
      while( true ) {
        RPC.RPCCall r;
        long currenTime = System.currentTimeMillis();
        for(H2ONode h2o:H2O.CLOUD._memary) {
          if(h2o != H2O.SELF) {
            for(RPCCall rpc:h2o._work.values()) {
              if((rpc._started + rpc._retry) < currenTime) {
                // RPC from somebody who dropped out of cloud?
                if( (!H2O.CLOUD.contains(rpc._client) && !rpc._client._heartbeat._client) ||
                  // Timedout client?
                  (rpc._client._heartbeat._client && rpc._retry >= HeartBeatThread.CLIENT_TIMEOUT) ) {
                  rpc._client.remove_task_tracking(rpc._tsknum);
                } else  {
                  if (rpc._computed) {
                    if (rpc._computedAndReplied) {
                      DTask dt = rpc._dt;
                      if(dt != null) {
                        if (++rpc._ackResendCnt % 5 == 0)
                          Log.warn("Got " + rpc._ackResendCnt + " resends on ack for task # " + rpc._tsknum + ", class = " + dt.getClass().getSimpleName());
                        rpc.resend_ack();
                      }
                    }
                  } else if(rpc._nackResendCnt == 0) { // else send nack
                    ++rpc._nackResendCnt;
                    rpc.send_nack();
                  }
                }
              }
            }
          }
        }
        long timeElapsed = System.currentTimeMillis()-currenTime;
        if(timeElapsed < 1000)
          try {Thread.sleep(1000-timeElapsed);} catch (InterruptedException e) {}
      }
    }
  }

  // This Node rebooted recently; we can quit tracking prior work history
  void rebooted() {
    _work.clear();
    _removed_task_ids.set(0);
  }

  // Custom Serialization Class: H2OKey need to be built.
  @Override public final AutoBuffer write_impl(AutoBuffer ab) { return _key.write(ab); }
  @Override public final H2ONode read_impl( AutoBuffer ab ) { return intern(H2Okey.read(ab)); }
  @Override public final AutoBuffer writeJSON_impl(AutoBuffer ab) { return ab.putJSONStr("node",_key.toString()); }
  @Override public final H2ONode readJSON_impl( AutoBuffer ab ) { throw H2O.fail(); }
}
