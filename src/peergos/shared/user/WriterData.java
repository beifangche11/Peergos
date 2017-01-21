package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.merklebtree.*;
import peergos.shared.storage.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class WriterData implements Cborable {
    /**
     *  Represents the merkle node that a public key maps to
     */

    // publicly readable and present on owner keys
    public final Optional<UserGenerationAlgorithm> generationAlgorithm;
    // accessible under IPFS address $hash/public
    public final Optional<FilePointer> publicData;
    // The public key to encrypt follow requests to, accessible under IPFS address $hash/inbound
    public final Optional<PublicBoxingKey> followRequestReceiver;
    // accessible under IPFS address $hash/owned
    public final Set<PublicSigningKey> ownedKeys;

    // Encrypted
    // accessible under IPFS address $hash/static (present on owner keys)
    public final Optional<UserStaticData> staticData;
    // accessible under IPFS address $hash/btree (present on writer keys)
    public final Optional<Multihash> btree;

    /**
     *
     * @param generationAlgorithm The algorithm used to create the users key pairs and root key from the username and password
     * @param publicData A readable pointer to a subtree made public by this key
     * @param ownedKeys Any public keys owned by this key
     * @param staticData Any static data owner by this key (list of entry points)
     * @param btree Any file tree owned by this key
     */
    public WriterData(Optional<UserGenerationAlgorithm> generationAlgorithm,
                      Optional<FilePointer> publicData,
                      Optional<PublicBoxingKey> followRequestReceiver,
                      Set<PublicSigningKey> ownedKeys,
                      Optional<UserStaticData> staticData,
                      Optional<Multihash> btree) {
        this.generationAlgorithm = generationAlgorithm;
        this.publicData = publicData;
        this.followRequestReceiver = followRequestReceiver;
        this.ownedKeys = ownedKeys;
        this.staticData = staticData;
        this.btree = btree;
    }

    public WriterData withBtree(Multihash treeRoot) {
        return new WriterData(generationAlgorithm, publicData, followRequestReceiver, ownedKeys, staticData, Optional.of(treeRoot));
    }

    public WriterData withOwnedKeys(Set<PublicSigningKey> owned) {
        return new WriterData(generationAlgorithm, publicData, followRequestReceiver, owned, staticData, btree);
    }

    public static WriterData buildSubtree(Multihash btreeRoot) {
        return new WriterData(Optional.empty(), Optional.empty(), Optional.empty(), Collections.emptySet(), Optional.empty(), Optional.of(btreeRoot));
    }

    public static WriterData createEmpty() {
        return new WriterData(Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Collections.emptySet(),
                Optional.empty(),
                Optional.empty());
    }

    public static WriterData createEmpty(Optional<PublicBoxingKey> followRequestReceiver, SymmetricKey rootKey) {
        return new WriterData(Optional.of(UserGenerationAlgorithm.getDefault()),
                Optional.empty(),
                followRequestReceiver,
                Collections.emptySet(),
                Optional.of(new UserStaticData(rootKey)),
                Optional.empty());
    }

    public CommittedWriterData committed(MaybeMultihash hash) {
        return new CommittedWriterData(hash, this);
    }

    public CompletableFuture<CommittedWriterData> removeFromStaticData(FileTreeNode fileTreeNode, SigningKeyPair signer,
                                                                       MaybeMultihash currentHash,
                                                                       NetworkAccess network, Consumer<CommittedWriterData> updater) {
        FilePointer pointer = fileTreeNode.getPointer().filePointer;

        return staticData.map(sd -> {
            boolean isRemoved = sd.remove(pointer);

            return isRemoved ? commit(signer, currentHash, network, updater) :
                    CompletableFuture.completedFuture(committed(currentHash));
        }).orElse(CompletableFuture.completedFuture(committed(currentHash)));
    }

    public CompletableFuture<CommittedWriterData> changeKeys(SigningKeyPair signer, MaybeMultihash currentHash,
                                                             PublicBoxingKey followRequestReceiver, SymmetricKey newKey,
                                                             NetworkAccess network, Consumer<CommittedWriterData> updater) {
        Optional<UserStaticData> newEntryPoints = staticData.map(sd -> sd.withKey(newKey));
        WriterData updated = new WriterData(generationAlgorithm, publicData, Optional.of(followRequestReceiver), ownedKeys, newEntryPoints, btree);
        return updated.commit(signer, MaybeMultihash.EMPTY(), network, updater);

    }

    public CompletableFuture<CommittedWriterData> commit(SigningKeyPair signer, MaybeMultihash currentHash,
                                                         NetworkAccess network, Consumer<CommittedWriterData> updater) {
        return commit(signer, currentHash, network.coreNode, network.dhtClient, updater);
    }

    public CompletableFuture<CommittedWriterData> commit(SigningKeyPair signer, MaybeMultihash currentHash,
                                                         CoreNode coreNode, ContentAddressedStorage dhtClient,
                                                         Consumer<CommittedWriterData> updater) {
        byte[] raw = serialize();
        return dhtClient.put(signer.publicSigningKey, raw, Collections.emptyList())
                .thenCompose(blobHash -> {
                            DataSink bout = new DataSink();
                            try {
                                currentHash.serialize(bout);
                                bout.writeArray(blobHash.toBytes());
                                byte[] signed = signer.signMessage(bout.toByteArray());
                                return coreNode.setMetadataBlob(signer.publicSigningKey, signer.publicSigningKey, signed)
                                        .thenApply(res -> {
                                            if (!res)
                                                throw new IllegalStateException("Corenode Crypto CAS failed!");
                                            CommittedWriterData committed = committed(MaybeMultihash.of(blobHash));
                                            updater.accept(committed);
                                            return committed;
                                        });
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> result = new TreeMap<>();

        generationAlgorithm.ifPresent(alg -> result.put("algorithm", alg.toCbor()));
        publicData.ifPresent(rfp -> result.put("public", rfp.toCbor()));
        followRequestReceiver.ifPresent(boxer -> result.put("inbound", boxer.toCbor()));
        List<CborObject> ownedKeyStrings = ownedKeys.stream().map(Cborable::toCbor).collect(Collectors.toList());
        result.put("owned", new CborObject.CborList(ownedKeyStrings));
        staticData.ifPresent(sd -> result.put("static", sd.toCbor()));
        btree.ifPresent(btree -> result.put("btree", new CborObject.CborMerkleLink(btree)));
        return CborObject.CborMap.build(result);
    }

    public static Optional<UserGenerationAlgorithm> extractUserGenerationAlgorithm(CborObject cbor) {
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Function<String, Optional<CborObject>> extract = key -> {
            CborObject.CborString cborKey = new CborObject.CborString(key);
            return map.values.containsKey(cborKey) ? Optional.of(map.values.get(cborKey)) : Optional.empty();
        };
        return extract.apply("algorithm").map(UserGenerationAlgorithm::fromCbor);
    }

    public static WriterData fromCbor(CborObject cbor, SymmetricKey rootKey) {
        if (! ( cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Cbor for WriterData should be a map! " + cbor);

        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Function<String, Optional<CborObject>> extract = key -> {
            CborObject.CborString cborKey = new CborObject.CborString(key);
            return map.values.containsKey(cborKey) ? Optional.of(map.values.get(cborKey)) : Optional.empty();
        };

        Optional<UserGenerationAlgorithm> algo  = extractUserGenerationAlgorithm(cbor);
        Optional<FilePointer> publicData = extract.apply("public").map(FilePointer::fromCbor);
        Optional<PublicBoxingKey> followRequestReceiver = extract.apply("inbound").map(raw -> PublicBoxingKey.fromCbor(raw));
        CborObject.CborList ownedList = (CborObject.CborList) map.values.get(new CborObject.CborString("owned"));
        Set<PublicSigningKey> owned = ownedList.value.stream().map(PublicSigningKey::fromCbor).collect(Collectors.toSet());
        // rootKey is null for other people parsing our WriterData who don't have our root key
        Optional<UserStaticData> staticData = rootKey == null ? Optional.empty() : extract.apply("static").map(raw -> UserStaticData.fromCbor(raw, rootKey));
        Optional<Multihash> btree = extract.apply("btree").map(val -> ((CborObject.CborMerkleLink)val).target);
        return new WriterData(algo, publicData, followRequestReceiver, owned, staticData, btree);
    }
}
