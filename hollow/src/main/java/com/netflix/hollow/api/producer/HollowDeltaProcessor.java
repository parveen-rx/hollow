/*
 *
 *  Copyright 2017 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.api.producer;

import com.netflix.hollow.api.consumer.HollowConsumer.BlobRetriever;
import com.netflix.hollow.api.producer.HollowProducer.Populator;
import com.netflix.hollow.api.producer.HollowProducer.ReadState;
import com.netflix.hollow.api.producer.HollowProducer.WriteState;
import com.netflix.hollow.core.index.HollowPrimaryKeyIndex;
import com.netflix.hollow.core.read.engine.HollowReadStateEngine;
import com.netflix.hollow.core.read.engine.HollowTypeReadState;
import com.netflix.hollow.core.schema.HollowObjectSchema;
import com.netflix.hollow.core.schema.HollowSchema;
import com.netflix.hollow.core.schema.HollowSchema.SchemaType;
import com.netflix.hollow.core.write.HollowTypeWriteState;
import com.netflix.hollow.core.write.objectmapper.RecordPrimaryKey;
import com.netflix.hollow.tools.traverse.TransitiveSetTraverser;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HollowDeltaProcessor {
    
    private static final Object DELETE_RECORD = new Object();

    private final HollowProducer producer;
    private final ConcurrentHashMap<RecordPrimaryKey, Object> mutations;
    
    public HollowDeltaProcessor(HollowProducer producer) {
        this.producer = producer;
        this.mutations = new ConcurrentHashMap<RecordPrimaryKey, Object>();
    }
    
    public void restore(long versionDesired, BlobRetriever blobRetriever) {
        producer.hardRestore(versionDesired, blobRetriever);
    }
    
    public void addOrModify(Object obj) {
        RecordPrimaryKey pk = producer.getObjectMapper().extractPrimaryKey(obj);
        mutations.put(pk, obj);
    }
    
    public void delete(Object obj) {
        RecordPrimaryKey pk = producer.getObjectMapper().extractPrimaryKey(obj);
        delete(pk);
    }
    
    public void delete(RecordPrimaryKey key) {
        mutations.put(key, DELETE_RECORD);
    }
    
    public long runCycle() {
        return producer.runCycle(new Populator() {
            public void populate(WriteState newState) throws Exception {
                newState.getStateEngine().addAllObjectsFromPreviousCycle();
                removeRecords(newState);
                addRecords(newState);
            }

            private void removeRecords(WriteState newState) {
                Map<String, BitSet> recordsToRemove = findTypesWithRemovedRecords(newState.getPriorState());
                markRecordsToRemove(newState.getPriorState(), recordsToRemove);
                removeRecordsFromNewState(newState, recordsToRemove);
            }

            private Map<String, BitSet> findTypesWithRemovedRecords(ReadState readState) {
                Map<String, BitSet> recordsToRemove = new HashMap<String, BitSet>();
                for(RecordPrimaryKey key : mutations.keySet()) {
                    if(!recordsToRemove.containsKey(key.getType())) {
                        BitSet bs = new BitSet(readState.getStateEngine().getTypeState(key.getType()).getPopulatedOrdinals().length());
                        recordsToRemove.put(key.getType(), bs);
                    }
                }
                return recordsToRemove;
            }

            private void markRecordsToRemove(ReadState priorState, Map<String, BitSet> recordsToRemove) {
                HollowReadStateEngine priorStateEngine = priorState.getStateEngine();
                
                for(Map.Entry<String, BitSet> removalEntry : recordsToRemove.entrySet()) {
                    markTypeRecordsToRemove(priorStateEngine, removalEntry.getKey(), removalEntry.getValue());
                }
                
                TransitiveSetTraverser.addTransitiveMatches(priorStateEngine, recordsToRemove);
                TransitiveSetTraverser.removeReferencedOutsideClosure(priorStateEngine, recordsToRemove);
            }

            private void markTypeRecordsToRemove(HollowReadStateEngine priorStateEngine, String type, BitSet typeRecordsToRemove) {
                HollowTypeReadState priorReadState = priorStateEngine.getTypeState(type);
                HollowSchema schema = priorReadState.getSchema();
                if(schema.getSchemaType() == SchemaType.OBJECT) {
                    HollowPrimaryKeyIndex idx = new HollowPrimaryKeyIndex(priorStateEngine, ((HollowObjectSchema) schema).getPrimaryKey()); ///TODO: Should we scan instead?  Can we create this once and do delta updates?
                    
                    for(Map.Entry<RecordPrimaryKey, Object> entry : mutations.entrySet()) {
                        if(entry.getKey().getType().equals(type)) {
                            int priorOrdinal = idx.getMatchingOrdinal(entry.getKey().getKey());
                            
                            if(priorOrdinal != -1)
                                typeRecordsToRemove.set(priorOrdinal);
                        }
                    }
                }
            }
            
            private void removeRecordsFromNewState(WriteState newState, Map<String, BitSet> recordsToRemove) {
                for(Map.Entry<String, BitSet> removalEntry : recordsToRemove.entrySet()) {
                    HollowTypeWriteState writeState = newState.getStateEngine().getTypeState(removalEntry.getKey());
                    BitSet typeRecordsToRemove = removalEntry.getValue();
                    
                    int ordinalToRemove = typeRecordsToRemove.nextSetBit(0);
                    while(ordinalToRemove != -1) {
                        writeState.removeOrdinalFromThisCycle(ordinalToRemove);
                        ordinalToRemove = typeRecordsToRemove.nextSetBit(ordinalToRemove+1);
                    }
                }
            }
            
            private void addRecords(WriteState newState) {
                for(Map.Entry<RecordPrimaryKey, Object> entry : mutations.entrySet()) {
                    if(entry.getValue() != DELETE_RECORD)
                        newState.add(entry.getValue());
                }
            }

        });
    }

}
