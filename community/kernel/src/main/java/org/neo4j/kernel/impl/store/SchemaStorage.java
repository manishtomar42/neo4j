/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.store;

import java.util.Collection;
import java.util.Iterator;
import java.util.function.Predicate;

import org.neo4j.function.Predicates;
import org.neo4j.helpers.collection.PrefetchingIterator;
import org.neo4j.kernel.api.exceptions.schema.DuplicateSchemaRuleException;
import org.neo4j.kernel.api.exceptions.schema.MalformedSchemaRuleException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.schema_new.SchemaDescriptor;
import org.neo4j.kernel.api.schema_new.SchemaDescriptorPredicates;
import org.neo4j.kernel.api.schema_new.constaints.ConstraintDescriptor;
import org.neo4j.kernel.api.schema_new.index.NewIndexDescriptor;
import org.neo4j.kernel.impl.store.record.ConstraintRule;
import org.neo4j.kernel.impl.store.record.DynamicRecord;
import org.neo4j.kernel.impl.store.record.IndexRule;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.storageengine.api.schema.SchemaRule;

public class SchemaStorage implements SchemaRuleAccess
{
    private final RecordStore<DynamicRecord> schemaStore;

    public SchemaStorage( RecordStore<DynamicRecord> schemaStore )
    {
        this.schemaStore = schemaStore;
    }

    /**
     * Find the IndexRule that matches the given NewIndexDescriptor.
     *
     * @return  the matching IndexRule, or null if no matching IndexRule was found
     * @throws  IllegalStateException if more than one matching rule.
     * @param descriptor the target NewIndexDescriptor
     */
    public IndexRule indexGetForSchema( final NewIndexDescriptor descriptor )
    {
        Iterator<IndexRule> rules = loadAllSchemaRules( descriptor::isSame, IndexRule.class, false );

        IndexRule foundRule = null;

        while ( rules.hasNext() )
        {
            IndexRule candidate = rules.next();
            if ( foundRule != null )
            {
                throw new IllegalStateException( String.format(
                        "Found more than one matching index rule, %s and %s", foundRule, candidate ) );
            }
            foundRule = candidate;
        }

        return foundRule;
    }

    public Iterator<IndexRule> indexesGetAll()
    {
        return loadAllSchemaRules( Predicates.alwaysTrue(), IndexRule.class, false );
    }

    public Iterator<ConstraintRule> constraintsGetAll()
    {
        return loadAllSchemaRules( Predicates.alwaysTrue(), ConstraintRule.class, false );
    }

    public Iterator<ConstraintRule> constraintsGetAllIgnoreMalformed()
    {
        return loadAllSchemaRules( Predicates.alwaysTrue(), ConstraintRule.class, true );
    }

    public Iterator<ConstraintRule> constraintsGetForRelType( int relTypeId )
    {
        return loadAllSchemaRules( rule -> SchemaDescriptorPredicates.hasRelType( rule, relTypeId ),
                ConstraintRule.class, false );
    }

    public Iterator<ConstraintRule> constraintsGetForLabel( int labelId )
    {
        return loadAllSchemaRules( rule -> SchemaDescriptorPredicates.hasLabel( rule, labelId ),
                ConstraintRule.class, false );
    }

    public Iterator<ConstraintRule> constraintsGetForSchema( SchemaDescriptor schemaDescriptor )
    {
        return loadAllSchemaRules( SchemaDescriptor.equalTo( schemaDescriptor ), ConstraintRule.class, false );
    }

    /**
     * Get the constraint rule that matches the given ConstraintDescriptor
     * @param descriptor the ConstraintDescriptor to match
     * @return the matching ConstrainRule
     * @throws SchemaRuleNotFoundException if no ConstraintRule matches the given descriptor
     * @throws DuplicateSchemaRuleException if two or more ConstraintRules match the given descriptor
     */
    public ConstraintRule constraintsGetSingle( final ConstraintDescriptor descriptor )
            throws SchemaRuleNotFoundException, DuplicateSchemaRuleException
    {
        Iterator<ConstraintRule> rules = loadAllSchemaRules( descriptor::isSame, ConstraintRule.class, false );

        if ( !rules.hasNext() )
        {
            throw new SchemaRuleNotFoundException( SchemaRule.Kind.map( descriptor ), descriptor.schema() );
        }

        ConstraintRule rule = rules.next();

        if ( rules.hasNext() )
        {
            throw new DuplicateSchemaRuleException( SchemaRule.Kind.map( descriptor ), descriptor.schema() );
        }
        return rule;
    }

    public Iterator<SchemaRule> loadAllSchemaRules()
    {
        return loadAllSchemaRules( Predicates.alwaysTrue(), SchemaRule.class, false );
    }

    @Override
    public SchemaRule loadSingleSchemaRule( long ruleId ) throws MalformedSchemaRuleException
    {
        return loadSingleSchemaRuleViaBuffer( ruleId, newRecordBuffer() );
    }

    <ReturnType extends SchemaRule> Iterator<ReturnType> loadAllSchemaRules(
            final Predicate<ReturnType> predicate,
            final Class<ReturnType> returnType,
            final boolean ignoreMalformed )
    {
        return new PrefetchingIterator<ReturnType>()
        {
            private final long highestId = schemaStore.getHighestPossibleIdInUse();
            private long currentId = 1; /*record 0 contains the block size*/
            private final byte[] scratchData = newRecordBuffer();
            private final DynamicRecord record = schemaStore.newRecord();

            @Override
            protected ReturnType fetchNextOrNull()
            {
                while ( currentId <= highestId )
                {
                    long id = currentId++;
                    schemaStore.getRecord( id, record, RecordLoad.FORCE );
                    if ( record.inUse() && record.isStartRecord() )
                    {
                        try
                        {
                            SchemaRule schemaRule = loadSingleSchemaRuleViaBuffer( id, scratchData );
                            if ( returnType.isInstance( schemaRule ) )
                            {
                                ReturnType returnRule = returnType.cast( schemaRule );
                                if ( predicate.test( returnRule ) )
                                {
                                    return returnRule;
                                }
                            }
                        }
                        catch ( MalformedSchemaRuleException e )
                        {
                            if ( !ignoreMalformed )
                            {
                                throw new RuntimeException( e );
                            }
                        }
                    }
                }
                return null;
            }
        };
    }

    private SchemaRule loadSingleSchemaRuleViaBuffer( long id, byte[] buffer ) throws MalformedSchemaRuleException
    {
        Collection<DynamicRecord> records;
        try
        {
            records = schemaStore.getRecords( id, RecordLoad.NORMAL );
        }
        catch ( Exception e )
        {
            throw new MalformedSchemaRuleException( e.getMessage(), e );
        }
        return SchemaStore.readSchemaRule( id, records, buffer );
    }

    public long newRuleId()
    {
        return schemaStore.nextId();
    }

    private byte[] newRecordBuffer()
    {
        return new byte[schemaStore.getRecordSize()*4];
    }
}
