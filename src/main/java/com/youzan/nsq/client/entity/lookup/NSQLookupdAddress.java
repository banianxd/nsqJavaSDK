package com.youzan.nsq.client.entity.lookup;

import com.fasterxml.jackson.databind.JsonNode;
import com.youzan.nsq.client.entity.*;
import com.youzan.nsq.client.exception.NSQLookupException;
import com.youzan.util.IOUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.net.URL;
import java.util.*;

/**
 * Created by lin on 16/12/13.
 */
public class NSQLookupdAddress extends AbstractLookupdAddress {
    private static final Logger logger = LoggerFactory.getLogger(NSQLookupdAddress.class);
    private static final String HTTP_PRO_HEAD = "http://";
    private static final String BROKER_QUERY_URL_WRITE = "%s/lookup?topic=%s&access=%s&metainfo=true";
    private static final String BROKER_QUERY_URL_READ = "%s/lookup?topic=%s&access=%s";

    protected int prefactor = 0;

    public NSQLookupdAddress(String clusterId, String address) {
        super(clusterId, address);
    }

    public static NSQLookupdAddress create(String clusterId, String lookupdAddrs) {
        return new NSQLookupdAddress(clusterId, lookupdAddrs);
    }

    public static NSQLookupdAddress create(String preClusterId, String previousLookupd, String curClusterId, String currentLookupd, int currentFactor) {
        return new NSQLookupdAddressPair(preClusterId, preClusterId, curClusterId, currentLookupd, currentFactor);
    }

    /**
     * return factor of previous lookup address(in %)
     * @return
     */
    public int getPreFactor(){
        return this.prefactor;
    }


    /**
     * lookup NSQ nodes from current {@link NSQLookupdAddress}, return mapping from cluster ID to partition nodes
     * @param topic
     * @param writable
     * @return
     */
    public IPartitionsSelector lookup(final Topic topic, boolean writable) throws NSQLookupException {
        if (null == topic || null == topic.getTopicText() || topic.getTopicText().isEmpty()) {
            throw new NSQLookupException("Your input topic is blank!");
        }
        /*
         * It is unnecessary to use Atomic/Lock for the variable
         */
        try{
            String lookup = this.getAddress();
            Partitions aPartitions = lookup(lookup, topic, writable);
            IPartitionsSelector ps = new SimplePartitionsSelector(aPartitions);
            return ps; // maybe it is empty
        } catch (Exception e) {
            final String tip = "SDK can't get the right lookup info. " + this.getAddress();
            throw new NSQLookupException(tip, e);
        }
    }

    protected Partitions lookup(String lookupdAddress, final Topic topic, boolean writable) throws IOException {
        if(!lookupdAddress.startsWith(HTTP_PRO_HEAD))
            lookupdAddress = HTTP_PRO_HEAD + lookupdAddress;
        String urlFormat = writable ? BROKER_QUERY_URL_WRITE : BROKER_QUERY_URL_READ ;
        final String url = String.format(urlFormat, lookupdAddress, topic.getTopicText(), writable ? "w" : "r");
        logger.debug("Begin to lookup some DataNodes from URL {}", url);
        Partitions aPartitions = new Partitions(topic);

        final JsonNode rootNode = IOUtil.readFromUrl(new URL(url));
        long start = 0L;
        if(logger.isDebugEnabled())
            start = System.currentTimeMillis();
        final JsonNode partitions = rootNode.get("partitions");
        Map<Integer, SoftReference<Address>> partitionId2Ref;
        List<Address> partitionedDataNodes;
        Set<Address> partitionNodeSet = new HashSet<>();

        List<Address> unPartitionedDataNodes = null;
        if (null != partitions) {

            partitionId2Ref = new HashMap<>();
            partitionedDataNodes = new ArrayList<>();

            int partitionCount = 0;
            //fetch meta info if it is writable
            if(writable) {
                JsonNode metaInfo = rootNode.get("meta");
                partitionCount = metaInfo.get("partition_num").asInt();
            }

            Iterator<String> irt = partitions.fieldNames();
            while (irt.hasNext()) {
                String parId = irt.next();
                int parIdInt = Integer.valueOf(parId);
                JsonNode partition = partitions.get(parId);
                final Address address = createAddress(partition);
                if(parIdInt >= 0) {
                    partitionedDataNodes.add(address);
                    partitionNodeSet.add(address);
                    partitionId2Ref.put(parIdInt, new SoftReference<>(address));
                    if(!writable)
                        partitionCount++;
                }
            }
            aPartitions.updatePartitionDataNode(partitionId2Ref, partitionedDataNodes, partitionCount);
            if(logger.isDebugEnabled()){
                logger.debug("SDK took {} mill sec to create mapping for partition.", (System.currentTimeMillis() - start));
            }
        }

        //producers part in json
        final JsonNode producers = rootNode.get("producers");
        for (JsonNode node : producers) {
            final Address address = createAddress(node);
            if(!partitionNodeSet.contains(address)) {
                if(null == unPartitionedDataNodes)
                    unPartitionedDataNodes = new ArrayList<>();
                unPartitionedDataNodes.add(address);
            }
        }

        if(null != unPartitionedDataNodes)
            aPartitions.updateUnpartitionedDataNodea(unPartitionedDataNodes);
        partitionNodeSet.clear();
        if(logger.isDebugEnabled())
            logger.debug("The server response info after looking up some DataNodes: {}", rootNode.toString());

        if(!aPartitions.hasAnyDataNodes()){
            logger.error("Could not find any NSQ data node from lookup address {}", this.getAddress());
            return null;
        }
        return aPartitions;
    }

    /**
     * create nsq broker Address, using pass in JsonNode,
     * specidied key in function will be used to extract
     * field to construct Address
     * @return Address nsq broker Address
     */
    protected Address createAddress(JsonNode node){
        final String host = node.get("broadcast_address").asText();
        final int port = node.get("tcp_port").asInt();
        final String version = node.get("version").asText();
        return new Address(host, port, version);
    }
}


