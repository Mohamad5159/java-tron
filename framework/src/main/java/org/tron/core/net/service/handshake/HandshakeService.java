package org.tron.core.net.service.handshake;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.core.ChainBaseManager;
import org.tron.core.config.args.Args;
import org.tron.core.net.TronNetService;
import org.tron.core.net.message.handshake.HelloMessage;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.peer.PeerManager;
import org.tron.core.net.service.effective.EffectiveCheckService;
import org.tron.core.net.service.relay.RelayService;
import org.tron.p2p.discover.Node;
import org.tron.protos.Protocol.ReasonCode;

@Slf4j(topic = "net")
@Component
public class HandshakeService {

  @Autowired
  private RelayService relayService;

  @Autowired
  private EffectiveCheckService effectiveCheckService;

  @Autowired
  private ChainBaseManager chainBaseManager;

  public void startHandshake(PeerConnection peer) {
    sendHelloMessage(peer, peer.getChannel().getStartTime());
  }

  public void processHelloMessage(PeerConnection peer, HelloMessage msg) {
    if (peer.getHelloMessageReceive() != null) {
      System.out.println("11111111111");
      logger.warn("Peer {} receive dup hello message", peer.getInetSocketAddress());
      peer.disconnect(ReasonCode.BAD_PROTOCOL);
      return;
    }

    TronNetService.getP2pService().updateNodeId(peer.getChannel(), msg.getFrom().getHexId());
    if (peer.isDisconnect()) {
      System.out.println("222222222");
      logger.info("Duplicate Peer {}", peer.getInetSocketAddress());
      peer.disconnect(ReasonCode.DUPLICATE_PEER);
      return;
    }

    if (!msg.valid()) {
      System.out.println("333333333");
      logger.warn("Peer {} invalid hello message parameters, "
                      + "GenesisBlockId: {}, SolidBlockId: {}, HeadBlockId: {}",
              peer.getInetSocketAddress(),
              ByteArray.toHexString(msg.getInstance().getGenesisBlockId().getHash().toByteArray()),
              ByteArray.toHexString(msg.getInstance().getSolidBlockId().getHash().toByteArray()),
              ByteArray.toHexString(msg.getInstance().getHeadBlockId().getHash().toByteArray()));
      peer.disconnect(ReasonCode.UNEXPECTED_IDENTITY);
      return;
    }

    peer.setAddress(msg.getHelloMessage().getAddress());

    if (!relayService.checkHelloMessage(msg, peer.getChannel())) {
      System.out.println("44444444444");
      peer.disconnect(ReasonCode.UNEXPECTED_IDENTITY);
      return;
    }

    long headBlockNum = chainBaseManager.getHeadBlockNum();
    long lowestBlockNum = msg.getLowestBlockNum();
    if (lowestBlockNum > headBlockNum) {
      System.out.println("55555555555");
      logger.info("Peer {} miss block, lowestBlockNum:{}, headBlockNum:{}",
              peer.getInetSocketAddress(), lowestBlockNum, headBlockNum);
      peer.disconnect(ReasonCode.LIGHT_NODE_SYNC_FAIL);
      return;
    }

    if (msg.getVersion() != Args.getInstance().getNodeP2pVersion()) {
      System.out.println("6666666666");
      logger.info("Peer {} different p2p version, peer->{}, me->{}",
              peer.getInetSocketAddress(), msg.getVersion(),
              Args.getInstance().getNodeP2pVersion());
      peer.disconnect(ReasonCode.INCOMPATIBLE_VERSION);
      return;
    }

    if (!Arrays.equals(chainBaseManager.getGenesisBlockId().getBytes(),
            msg.getGenesisBlockId().getBytes())) {
      System.out.println("7777777777");
      logger.info("Peer {} different genesis block, peer->{}, me->{}",
              peer.getInetSocketAddress(),
              msg.getGenesisBlockId().getString(),
              chainBaseManager.getGenesisBlockId().getString());
      peer.disconnect(ReasonCode.INCOMPATIBLE_CHAIN);
      return;
    }

    if (chainBaseManager.getSolidBlockId().getNum() >= msg.getSolidBlockId().getNum()
            && !chainBaseManager.containBlockInMainChain(msg.getSolidBlockId())) {
      System.out.println("88888888888");
      logger.info("Peer {} different solid block, peer->{}, me->{}",
              peer.getInetSocketAddress(),
              msg.getSolidBlockId().getString(),
              chainBaseManager.getSolidBlockId().getString());
      peer.disconnect(ReasonCode.FORKED);
      return;
    }

    if (msg.getHeadBlockId().getNum() < chainBaseManager.getHeadBlockId().getNum()
        && peer.getInetSocketAddress().equals(effectiveCheckService.getCur())) {
      System.out.println("99999999999");
      logger.info("Peer's head block {} is below than we, peer->{}, me->{}",
          peer.getInetSocketAddress(), msg.getHeadBlockId().getNum(),
          chainBaseManager.getHeadBlockId().getNum());
      peer.disconnect(ReasonCode.BELOW_THAN_ME);
      return;
    }

    System.out.println("000000000");
    peer.setHelloMessageReceive(msg);

    peer.getChannel().updateAvgLatency(
            System.currentTimeMillis() - peer.getChannel().getStartTime());
    PeerManager.sortPeers();
    peer.onConnect();
  }

  private void sendHelloMessage(PeerConnection peer, long time) {
    Node node = new Node(TronNetService.getP2pConfig().getNodeID(),
        TronNetService.getP2pConfig().getIp(),
        TronNetService.getP2pConfig().getIpv6(),
        TronNetService.getP2pConfig().getPort());
    HelloMessage message = new HelloMessage(node, time, ChainBaseManager.getChainBaseManager());
    relayService.fillHelloMessage(message, peer.getChannel());
    peer.sendMessage(message);
    peer.setHelloMessageSend(message);
  }

}
