/*
 * Copyright 2009 Thomas Bocek
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package net.tomp2p.rpc;

import java.io.IOException;
import java.security.PublicKey;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.concurrent.locks.Lock;

import net.tomp2p.connection2.ChannelCreator;
import net.tomp2p.connection2.ConnectionBean;
import net.tomp2p.connection2.PeerBean;
import net.tomp2p.connection2.RequestHandler;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.message.DataMap;
import net.tomp2p.message.Keys;
import net.tomp2p.message.KeysMap;
import net.tomp2p.message.Message2;
import net.tomp2p.message.Message2.Type;
import net.tomp2p.p2p.builder.AddBuilder;
import net.tomp2p.p2p.builder.GetBuilder;
import net.tomp2p.p2p.builder.PutBuilder;
import net.tomp2p.p2p.builder.RemoveBuilder;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number320;
import net.tomp2p.peers.Number480;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.storage.Data;
import net.tomp2p.storage.StorageGeneric.PutStatus;
import net.tomp2p.utils.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RPC that deals with storage.
 * 
 * @author Thomas Bocek
 * 
 */
public class StorageRPC extends DispatchHandler {
    private static final Logger LOG = LoggerFactory.getLogger(StorageRPC.class);
    private static final Random RND = new Random();

    public static final byte COMMAND_PUT = 1;
    public static final byte COMMAND_GET = 2;
    public static final byte COMMAND_ADD = 3;
    public static final byte COMMAND_REMOVE = 4;

    private final BloomfilterFactory factory;

    /**
     * Register the store rpc for put, compare put, get, add, and remove.
     * 
     * @param peerBean
     *            The peer bean
     * @param connectionBean
     *            The connection bean
     */
    public StorageRPC(final PeerBean peerBean, final ConnectionBean connectionBean) {
        super(peerBean, connectionBean, COMMAND_PUT, COMMAND_GET, COMMAND_ADD, COMMAND_REMOVE);
        this.factory = peerBean.bloomfilterFactory();
    }

    /**
     * Stores data on a remote peer. Overwrites data if the data already exists. This is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to store the data
     * @param locationKey
     *            The location of the data
     * @param domainKey
     *            The domain of the data
     * @param dataMap
     *            The map with the content key and data
     * @param protectDomain
     *            Set to true if the domain should be set to protected. This means that this domain is flagged an a
     *            public key is stored for this entry. An update or removal can only be made with the matching private
     *            key.
     * @param protectEntry
     *            Set to true if the entry should be set to protected. This means that this domain is flagged an a
     *            public key is stored for this entry. An update or removal can only be made with the matching private
     *            key.
     * @param signMessage
     *            Set to true if the message should be signed. For protecting an entry, this needs to be set to true.
     * @param channelCreator
     *            The channel creator
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return FutureResponse that stores which content keys have been stored.
     */
    public FutureResponse put(final PeerAddress remotePeer, final PutBuilder putBuilder,
            final ChannelCreator channelCreator) {
        final Type request = putBuilder.isProtectDomain() ? Type.REQUEST_2 : Type.REQUEST_1;
        return put(remotePeer, putBuilder, request, channelCreator);
    }

    /**
     * Stores data on a remote peer. Only stores data if the data does not already exist. This is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to store the data
     * @param locationKey
     *            The location of the data
     * @param domainKey
     *            The domain of the data
     * @param dataMap
     *            The map with the content key and data
     * @param protectDomain
     *            Set to true if the domain should be set to protected. This means that this domain is flagged an a
     *            public key is stored for this entry. An update or removal can only be made with the matching private
     *            key.
     * @param protectEntry
     *            Set to true if the entry should be set to protected. This means that this domain is flagged an a
     *            public key is stored for this entry. An update or removal can only be made with the matching private
     *            key.
     * @param signMessage
     *            Set to true if the message should be signed. For protecting an entry, this needs to be set to true.
     * @param channelCreator
     *            The channel creator
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return FutureResponse that stores which content keys have been stored.
     */
    public FutureResponse putIfAbsent(final PeerAddress remotePeer, final PutBuilder putBuilder,
            final ChannelCreator channelCreator) {
        final Type request;
        if (putBuilder.isProtectDomain()) {
            request = Type.REQUEST_4;
        } else {
            request = Type.REQUEST_3;
        }
        return put(remotePeer, putBuilder, request, channelCreator);
    }

    /**
     * Stores the data either via put or putIfAbsent. This is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to store the data
     * @param locationKey
     *            The location key
     * @param domainKey
     *            The domain key
     * @param dataMap
     *            The map with the content key and data
     * @param type
     *            The type of put request, this depends on put/putIfAbsent/protected/not-protected
     * @param signMessage
     *            Set to true to sign message
     * @param channelCreator
     *            The channel creator
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return FutureResponse that stores which content keys have been stored.
     */
    /*
     * private FutureResponse put(final PeerAddress remotePeer, final Number160 locationKey, final Number160 domainKey,
     * final Map<Number160, Data> dataMap, final Type type, boolean signMessage, ChannelCreator channelCreator, boolean
     * forceUDP, SenderCacheStrategy senderCacheStrategy) {
     */
    private FutureResponse put(final PeerAddress remotePeer, final PutBuilder putBuilder, final Type type,
            final ChannelCreator channelCreator) {

        Utils.nullCheck(remotePeer);

        final DataMap dataMap;
        if (putBuilder.getDataMap() != null) {
            dataMap = new DataMap(putBuilder.getDataMap());
        } else {
            dataMap = new DataMap(putBuilder.getLocationKey(), putBuilder.getDomainKey(),
                    putBuilder.getDataMapContent());
        }

        final Message2 message = createMessage(remotePeer, COMMAND_PUT, type);

        if (putBuilder.isSignMessage()) {
            message.setPublicKeyAndSign(peerBean().getKeyPair());
        }

        message.setDataMap(dataMap);

        final FutureResponse futureResponse = new FutureResponse(message);
        final RequestHandler<FutureResponse> request = new RequestHandler<FutureResponse>(futureResponse,
                peerBean(), connectionBean(), putBuilder);

        if (!putBuilder.isForceUDP()) {
            return request.sendTCP(channelCreator);
        } else {
            return request.sendUDP(channelCreator);
        }

    }

    /**
     * Adds data on a remote peer. The main difference to
     * {@link #put(PeerAddress, Number160, Number160, Map, Type, boolean, ChannelCreator, boolean)} and
     * {@link #putIfAbsent(PeerAddress, Number160, Number160, Map, boolean, boolean, boolean, ChannelCreator, boolean)}
     * is that it will convert the data collection to map. The key for the map will be the SHA-1 hash of the data. This
     * is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to store the data
     * @param locationKey
     *            The location key
     * @param domainKey
     *            The domain key
     * @param dataSet
     *            The set with data. This will be converted to a map. The key for the map is the SHA-1 of the data.
     * @param protectDomain
     *            Set to true if the domain should be set to protected. This means that this domain is flagged an a
     *            public key is stored for this entry. An update or removal can only be made with the matching private
     *            key.
     * @param signMessage
     *            Set to true if the message should be signed. For protecting an entry, this needs to be set to true.
     * @param channelCreator
     *            The channel creator
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return FutureResponse that stores which content keys have been stored.
     */
    public FutureResponse add(final PeerAddress remotePeer, final AddBuilder addBuilder,
            ChannelCreator channelCreator) {
        Utils.nullCheck(remotePeer, addBuilder.getLocationKey(), addBuilder.getDomainKey());
        final Type type;
        if (addBuilder.isProtectDomain()) {
            if (addBuilder.isList()) {
                type = Type.REQUEST_4;
            } else {
                type = Type.REQUEST_2;
            }
        } else {
            if (addBuilder.isList()) {
                type = Type.REQUEST_3;
            } else {
                type = Type.REQUEST_1;
            }
        }

        // convert the data
        Map<Number160, Data> dataMap = new HashMap<Number160, Data>(addBuilder.getDataSet().size());
        if (addBuilder.getDataSet() != null) {
            for (Data data : addBuilder.getDataSet()) {
                if (addBuilder.isList()) {
                    Number160 hash = new Number160(addBuilder.random());
                    while (dataMap.containsKey(hash)) {
                        hash = new Number160(addBuilder.random());
                    }
                    dataMap.put(hash, data);
                } else {
                    dataMap.put(data.hash(), data);
                }
            }
        }

        final Message2 message = createMessage(remotePeer, COMMAND_ADD, type);

        if (addBuilder.isSignMessage()) {
            message.setPublicKeyAndSign(peerBean().getKeyPair());
        }

        message.setDataMap(new DataMap(addBuilder.getLocationKey(), addBuilder.getDomainKey(), dataMap));

        final FutureResponse futureResponse = new FutureResponse(message);
        final RequestHandler<FutureResponse> request = new RequestHandler<FutureResponse>(futureResponse,
                peerBean(), connectionBean(), addBuilder);
        if (!addBuilder.isForceUDP()) {
            return request.sendTCP(channelCreator);
        } else {
            return request.sendUDP(channelCreator);
        }

    }

    /**
     * Get the data from a remote peer. This is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to send this request
     * @param locationKey
     *            The location key
     * @param domainKey
     *            The domain key
     * @param contentKeys
     *            The content keys or null if requested all
     * @param signMessage
     *            Adds a public key and signs the message
     * @param digest
     *            Returns a list of hashes of the data stored on this peer
     * @param channelCreator
     *            The channel creator that creates connections. Typically we need one connection here.
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return The future response to keep track of future events
     */
    public FutureResponse get(final PeerAddress remotePeer, final GetBuilder getBuilder,
            final ChannelCreator channelCreator) {
        Type type;
        if (getBuilder.isRange() && !getBuilder.isDigest()) {
            type = Type.REQUEST_4;
        } else if (!getBuilder.isRange() && !getBuilder.isDigest()) {
            type = Type.REQUEST_1;
        } else if (getBuilder.isDigest() && !getBuilder.isReturnBloomFilter()) {
            type = Type.REQUEST_2;
        } else { // if(digest && returnBloomFilter)
            type = Type.REQUEST_3;
        }
        final Message2 message = createMessage(remotePeer, COMMAND_GET, type);

        if (getBuilder.isSignMessage()) {
            message.setPublicKeyAndSign(peerBean().getKeyPair());
        }

        if (getBuilder.keys() == null) {

            if (getBuilder.getLocationKey() == null || getBuilder.getDomainKey() == null) {
                throw new IllegalArgumentException("Null not allowed in location or domain");
            }
            message.setKey(getBuilder.getLocationKey());
            message.setKey(getBuilder.getDomainKey());

            if (getBuilder.getContentKeys() != null) {
                message.setKeys(new Keys(getBuilder.getLocationKey(), getBuilder.getDomainKey(), getBuilder
                        .getContentKeys()));
            } else if (getBuilder.getKeyBloomFilter() != null || getBuilder.getContentBloomFilter() != null) {
                if (getBuilder.getKeyBloomFilter() != null) {
                    message.setBloomFilter(getBuilder.getKeyBloomFilter());
                }
                if (getBuilder.getContentBloomFilter() != null) {
                    message.setBloomFilter(getBuilder.getContentBloomFilter());
                }
            }
        } else {
            message.setKeys(new Keys(getBuilder.keys()));
        }

        final FutureResponse futureResponse = new FutureResponse(message);
        final RequestHandler<FutureResponse> request = new RequestHandler<FutureResponse>(futureResponse,
                peerBean(), connectionBean(), getBuilder);
        if (!getBuilder.isForceUDP()) {
            return request.sendTCP(channelCreator);
        } else {
            return request.sendUDP(channelCreator);
        }
    }

    /**
     * Removes data from a peer. This is an RPC.
     * 
     * @param remotePeer
     *            The remote peer to send this request
     * @param locationKey
     *            The location key
     * @param domainKey
     *            The domain key
     * @param contentKeys
     *            The content keys or null if requested all
     * @param sendBackResults
     *            Set to true if the removed data should be sent back
     * @param signMessage
     *            Adds a public key and signs the message. For protected entry and domains, this needs to be provided.
     * @param channelCreator
     *            The channel creator that creates connections
     * @param forceUDP
     *            Set to true if the communication should be UDP, default is TCP
     * @return The future response to keep track of future events
     */
    public FutureResponse remove(final PeerAddress remotePeer, final RemoveBuilder removeBuilder,
            final ChannelCreator channelCreator) {
        final Message2 message = createMessage(remotePeer, COMMAND_REMOVE,
                removeBuilder.isReturnResults() ? Type.REQUEST_2 : Type.REQUEST_1);

        if (removeBuilder.isSignMessage()) {
            message.setPublicKeyAndSign(peerBean().getKeyPair());
        }

        if (removeBuilder.getKeys() == null) {

            if (removeBuilder.getLocationKey() == null || removeBuilder.getDomainKey() == null) {
                throw new IllegalArgumentException("Null not allowed in location or domain");
            }
            message.setKey(removeBuilder.getLocationKey());
            message.setKey(removeBuilder.getDomainKey());

            if (removeBuilder.getContentKeys() != null) {
                message.setKeys(new Keys(removeBuilder.getLocationKey(), removeBuilder.getDomainKey(),
                        removeBuilder.getContentKeys()));
            }
        } else {
            message.setKeys(new Keys(removeBuilder.getKeys()));
        }

        final FutureResponse futureResponse = new FutureResponse(message);

        final RequestHandler<FutureResponse> request = new RequestHandler<FutureResponse>(futureResponse,
                peerBean(), connectionBean(), removeBuilder);
        if (!removeBuilder.isForceUDP()) {
            return request.sendTCP(channelCreator);
        } else {
            return request.sendUDP(channelCreator);
        }
    }

    @Override
    public Message2 handleResponse(final Message2 message, final boolean sign) throws Exception {

        if (!(message.getCommand() == COMMAND_ADD || message.getCommand() == COMMAND_PUT
                || message.getCommand() == COMMAND_GET || message.getCommand() == COMMAND_REMOVE)) {
            throw new IllegalArgumentException("Message content is wrong");
        }
        final Message2 responseMessage = createResponseMessage(message, Type.OK);

        switch (message.getCommand()) {
        case COMMAND_ADD:
            handleAdd(message, responseMessage, isDomainProtected(message));
            break;
        case COMMAND_PUT:
            handlePut(message, responseMessage, isStoreIfAbsent(message), isDomainProtected(message));
            break;
        case COMMAND_GET:
            final boolean range = message.getType() == Type.REQUEST_4;
            final boolean digest = message.getType() == Type.REQUEST_2 || message.getType() == Type.REQUEST_3;
            handleGet(message, responseMessage, range, digest);
            break;
        case COMMAND_REMOVE:
            handleRemove(message, responseMessage, message.getType() == Type.REQUEST_2);
            break;
        default:
            throw new IllegalArgumentException("Message content is wrong");
        }
        if (sign) {
            responseMessage.setPublicKeyAndSign(peerBean().getKeyPair());
        }
        return responseMessage;
    }

    private boolean isDomainProtected(final Message2 message) {
        boolean protectDomain = message.getPublicKey() != null
                && (message.getType() == Type.REQUEST_2 || message.getType() == Type.REQUEST_4);
        return protectDomain;
    }

    private boolean isStoreIfAbsent(final Message2 message) {
        boolean absent = message.getType() == Type.REQUEST_3 || message.getType() == Type.REQUEST_4;
        return absent;
    }

    private boolean isPartial(final Message2 message) {
        boolean partial = message.getType() == Type.REQUEST_3 || message.getType() == Type.REQUEST_4;
        return partial;
    }

    private boolean isList(final Message2 message) {
        boolean partial = message.getType() == Type.REQUEST_3 || message.getType() == Type.REQUEST_4;
        return partial;
    }

    private Message2 handlePut(final Message2 message, final Message2 responseMessage,
            final boolean putIfAbsent, final boolean protectDomain) throws IOException {
        final PublicKey publicKey = message.getPublicKey();
        final DataMap toStore = message.getDataMap(0);
        final int dataSize = toStore.size();
        final Collection<Number480> result = new HashSet<Number480>(dataSize);
        for (Map.Entry<Number480, Data> entry : toStore.dataMap().entrySet()) {
            if (doPut(putIfAbsent, protectDomain, publicKey, entry.getKey().getLocationKey(), entry.getKey()
                    .getDomainKey(), entry.getKey().getContentKey(), entry.getValue())) {
                result.add(entry.getKey());
                // check the responsibility of the newly added data, do something
                // (notify) if we are responsible
                if (peerBean().replicationStorage() != null) {
                    peerBean().replicationStorage().updateAndNotifyResponsibilities(
                            entry.getKey().getLocationKey());
                }
            }
        }

        if (result.size() == 0 && !putIfAbsent) {
            responseMessage.setType(Type.DENIED);
        } else if (result.size() == 0 && putIfAbsent) {
            // put if absent does not return an error if it did not work!
            responseMessage.setType(Type.OK);
            responseMessage.setKeys(new Keys(result));
        } else {
            responseMessage.setKeys(new Keys(result));
            if (result.size() != dataSize) {
                responseMessage.setType(Type.PARTIALLY_OK);
            }
        }
        return responseMessage;
    }

    private boolean doPut(final boolean putIfAbsent, final boolean protectDomain, final PublicKey publicKey,
            final Number160 locationKey, final Number160 domainKey, final Number160 contentKey,
            final Data value) {
        if (peerBean().storage().put(locationKey, domainKey, contentKey, value, publicKey, putIfAbsent,
                protectDomain) == PutStatus.OK) {
            LOG.debug("put data with key {}, domain {} on {}", locationKey, domainKey, peerBean()
                    .serverPeerAddress());
            return true;
        } else {
            LOG.debug("could not add {}, domain {} on {}", locationKey, domainKey, peerBean()
                    .serverPeerAddress());
            return false;
        }
    }

    private Message2 handleAdd(final Message2 message, final Message2 responseMessage,
            final boolean protectDomain) {

        Utils.nullCheck(message.getDataMap(0));

        final Collection<Number480> result = new HashSet<Number480>();
        final DataMap dataMap = message.getDataMap(0);
        final PublicKey publicKey = message.getPublicKey();
        final boolean list = isList(message);
        // here we set the map with the close peers. If we get data by a
        // sender and the sender is closer than us, we assume that the sender has
        // the data and we don't need to transfer data to the closest (sender)
        // peer.

        for (Map.Entry<Number480, Data> entry : dataMap.dataMap().entrySet()) {
            Number160 contentKey = doAdd(protectDomain, entry, publicKey, list, peerBean());
            if (contentKey != null) {
                result.add(new Number480(entry.getKey().getLocationKey(), entry.getKey().getDomainKey(),
                        contentKey));
            }
            // check the responsibility of the newly added data, do something
            // (notify) if we are responsible
            if (result.size() > 0 && peerBean().replicationStorage() != null) {
                peerBean().replicationStorage().updateAndNotifyResponsibilities(
                        entry.getKey().getLocationKey());
            }

        }
        responseMessage.setKeys(new Keys(result));
        return responseMessage;
    }

    private static Number160 doAdd(final boolean protectDomain, final Map.Entry<Number480, Data> entry,
            final PublicKey publicKey, final boolean list, final PeerBean peerBean) {
        final Number160 locationKey = entry.getKey().getLocationKey();
        final Number160 domainKey = entry.getKey().getDomainKey();
        if (list) {
            Number160 contentKey2 = new Number160(RND);
            PutStatus status;
            while ((status = peerBean.storage().put(locationKey, domainKey, contentKey2, entry.getValue(),
                    publicKey, true, protectDomain)) == PutStatus.FAILED_NOT_ABSENT) {
                contentKey2 = new Number160(RND);
            }
            if (status == PutStatus.OK) {
                LOG.debug("add list data with key {} on {}", locationKey, peerBean.serverPeerAddress());
                return contentKey2;
            }
        } else {
            if (peerBean.storage().put(locationKey, domainKey, entry.getKey().getContentKey(),
                    entry.getValue(), publicKey, false, protectDomain) == PutStatus.OK) {
                LOG.debug("add data with key {} on {}", locationKey, peerBean.serverPeerAddress());
                return entry.getKey().getContentKey();
            }
        }
        return null;
    }

    private Message2 handleGet(final Message2 message, final Message2 responseMessage, final boolean range,
            final boolean digest) {
        final Number160 locationKey = message.getKey(0);
        final Number160 domainKey = message.getKey(1);
        final Keys contentKeys = message.getKeys(0);
        final SimpleBloomFilter<Number160> keyBloomFilter = message.getBloomFilter(0);
        final SimpleBloomFilter<Number160> contentBloomFilter = message.getBloomFilter(1);

        if (digest) {
            final DigestInfo digestInfo;
            if (keyBloomFilter != null || contentBloomFilter != null
                    && (locationKey != null && domainKey != null)) {
                digestInfo = peerBean().storage().digest(locationKey, domainKey, keyBloomFilter,
                        contentBloomFilter);
            } else if (locationKey != null && domainKey != null && contentKeys == null) {
                digestInfo = peerBean().storage().digest(locationKey, domainKey, null);
            } else if (contentKeys != null) {
                digestInfo = peerBean().storage().digest(contentKeys.keys());
            } else {
                throw new IllegalArgumentException("need at least two keys, bloomfilter, or key set");
            }
            if (message.getType() == Type.REQUEST_2) {
                responseMessage.setKeysMap(new KeysMap(digestInfo.getDigests()));
            } else if (message.getType() == Type.REQUEST_3) {
                // we did not specifically set location and domain key, this means we want to see what location and
                // domains we have
                if (locationKey == null && domainKey == null) {
                    responseMessage.setBloomFilter(digestInfo.getLocationKeyBloomFilter(factory));
                    responseMessage.setBloomFilter(digestInfo.getDomainKeyBloomFilter(factory));
                }
                responseMessage.setBloomFilter(digestInfo.getContentKeyBloomFilter(factory));
                responseMessage.setBloomFilter(digestInfo.getContentBloomFilter(factory));
            }
            return responseMessage;
        } else {
            final Map<Number480, Data> result;
            if (contentKeys != null) {
                result = new HashMap<Number480, Data>();
                if (!range || contentKeys.size() != 2) {
                    for (Number480 key : contentKeys.keys()) {
                        Data data = peerBean().storage().get(key.getLocationKey(), key.getDomainKey(),
                                key.getContentKey());
                        if (data != null) {
                            result.put(key, data);
                        }
                    }
                } else {
                    // get min/max
                    Iterator<Number480> iterator = contentKeys.keys().iterator();
                    Number160 min = iterator.next().getContentKey();
                    Number160 max = iterator.next().getContentKey();
                    SortedMap<Number480, Data> map = peerBean().storage().get(locationKey, domainKey, min,
                            max);
                    Number320 lockKey = new Number320(locationKey, domainKey);
                    Lock lock = peerBean().storage().getLockNumber320().lock(lockKey);
                    try {
                        result.putAll(map);
                    } finally {
                        peerBean().storage().getLockNumber320().unlock(lockKey, lock);
                    }
                }
            } else if (keyBloomFilter != null || contentBloomFilter != null) {
                result = new HashMap<Number480, Data>();
                SortedMap<Number480, Data> tmp = peerBean().storage().get(locationKey, domainKey,
                        Number160.ZERO, Number160.MAX_VALUE);
                Number320 lockKey = new Number320(locationKey, domainKey);
                Lock lock = peerBean().storage().getLockNumber320().lock(lockKey);
                try {
                    for (Map.Entry<Number480, Data> entry : tmp.entrySet()) {
                        if (keyBloomFilter == null || keyBloomFilter.contains(entry.getKey().getContentKey())) {
                            if (contentBloomFilter == null
                                    || contentBloomFilter.contains(entry.getValue().hash())) {
                                result.put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                } finally {
                    peerBean().storage().getLockNumber320().unlock(lockKey, lock);
                }
            } else {
                result = peerBean().storage()
                        .get(locationKey, domainKey, Number160.ZERO, Number160.MAX_VALUE);
            }
            responseMessage.setDataMap(new DataMap(result));
            return responseMessage;
        }
    }

    private Message2 handleRemove(final Message2 message, final Message2 responseMessage,
            final boolean sendBackResults) {
        final Number160 locationKey = message.getKey(0);
        final Number160 domainKey = message.getKey(1);
        final Keys keys = message.getKeys(0);
        final PublicKey publicKey = message.getPublicKey();
        final Map<Number480, Data> result;
        if (keys != null) {
            result = new HashMap<Number480, Data>(keys.size());
            for (Number480 key : keys.keys()) {
                Data data = peerBean().storage().remove(key.getLocationKey(), key.getDomainKey(),
                        key.getContentKey(), publicKey);
                if (data != null) {
                    result.put(key, data);
                }
            }
        } else if (locationKey != null && domainKey != null) {
            result = peerBean().storage().remove(locationKey, domainKey, Number160.ZERO, Number160.MAX_VALUE,
                    publicKey);
        } else {
            throw new IllegalArgumentException("Either two keys or a key set are necessary");
        }
        if (!sendBackResults) {
            // make a copy, so the iterator in the codec wont conflict with
            // concurrent calls
            responseMessage.setKeys(new Keys(result.keySet()));
        } else {
            // make a copy, so the iterator in the codec wont conflict with
            // concurrent calls
            responseMessage.setDataMap(new DataMap(result));
        }
        return responseMessage;
    }
}
