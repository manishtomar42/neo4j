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
package org.neo4j.kernel.api.index;

import java.util.HashMap;
import java.util.Map;

import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.api.schema_new.LabelSchemaDescriptor;

public class NodePropertyAccessor implements PropertyAccessor
{
    private final Map<Long, Map<Integer,Object>> nodePropertyMap;

    NodePropertyAccessor( long nodeId, LabelSchemaDescriptor schema, Object... values )
    {
        nodePropertyMap = new HashMap<>();
        addNode( nodeId, schema, values );
    }

    public void addNode( long nodeId, LabelSchemaDescriptor schema, Object... values )
    {
        Map<Integer,Object> propertyMap = new HashMap<>();
        for ( int i = 0; i < schema.getPropertyIds().length; i++ )
        {
            propertyMap.put( schema.getPropertyIds()[i], values[i] );
        }
        nodePropertyMap.put( nodeId, propertyMap );
    }

    @Override
    public Property getProperty( long nodeId, int propertyKeyId ) throws EntityNotFoundException
    {
        if ( nodePropertyMap.containsKey( nodeId ) )
        {
            Object value = nodePropertyMap.get( nodeId ).get( propertyKeyId );
            if ( value == null )
            {
                return Property.noNodeProperty( nodeId, propertyKeyId );
            }
            else
            {
                return Property.property( propertyKeyId, value );
            }
        }
        return Property.noNodeProperty( nodeId, propertyKeyId );
    }
}
