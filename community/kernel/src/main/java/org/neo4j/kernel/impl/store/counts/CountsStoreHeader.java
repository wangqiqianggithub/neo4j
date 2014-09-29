/**
 * Copyright (c) 2002-2014 "Neo Technology,"
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
package org.neo4j.kernel.impl.store.counts;

import java.io.IOException;

import org.neo4j.helpers.UTF8;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;

import static org.neo4j.io.pagecache.PagedFile.PF_EXCLUSIVE_LOCK;
import static org.neo4j.kernel.impl.store.counts.CountsStore.RECORD_SIZE;
import static org.neo4j.kernel.impl.transaction.log.TransactionIdStore.BASE_TX_ID;

final class CountsStoreHeader
{
    static CountsStoreHeader empty( String storeFormatVersion )
    {
        return new CountsStoreHeader( UTF8.encode( storeFormatVersion ), 0, BASE_TX_ID );
    }

    private static final int META_HEADER_SIZE = 2/*headerRecords*/ + 2/*versionLen*/ + 4/*dataRecords*/ + 8/*lastTxId*/;
    private final byte[] storeFormatVersion;
    private final int dataRecords;
    private final long lastTxId;

    private CountsStoreHeader( byte[] storeFormatVersion, int dataRecords, long lastTxId )
    {
        this.storeFormatVersion = storeFormatVersion;
        this.dataRecords = dataRecords;
        this.lastTxId = lastTxId;
    }

    @Override
    public String toString()
    {
        return String.format( "%s[storeFormatVersion=%s, dataRecords=%d, lastTxId=%d]",
                              getClass().getSimpleName(), storeFormatVersion(), dataRecords, lastTxId );
    }

    CountsStoreHeader update( int dataRecords, long lastTxId )
    {
        return new CountsStoreHeader( storeFormatVersion, dataRecords, lastTxId );
    }

    String storeFormatVersion()
    {
        return UTF8.decode( storeFormatVersion );
    }

    int headerRecords()
    {
        int headerBytes = META_HEADER_SIZE + storeFormatVersion.length;
        headerBytes += RECORD_SIZE - (headerBytes % RECORD_SIZE);
        return headerBytes / RECORD_SIZE;
    }

    int dataRecords()
    {
        return dataRecords;
    }

    long lastTxId()
    {
        return lastTxId;
    }

    static CountsStoreHeader read( PagedFile pagedFile ) throws IOException
    {
        try ( PageCursor page = pagedFile.io( 0, PF_EXCLUSIVE_LOCK ) )
        {
            if ( page.next() )
            {
                short headerRecords;
                short versionLength;
                int dataRecords;
                long lastTxId;
                byte[] storeFormatVersion;
                do
                {
                    page.setOffset( 0 );
                    headerRecords = page.getShort();
                    versionLength = page.getShort();
                    dataRecords = page.getInt();
                    lastTxId = page.getLong();
                    int versionSpace = headerRecords * RECORD_SIZE - META_HEADER_SIZE;
                    if ( versionLength > versionSpace || versionLength < (versionSpace - RECORD_SIZE) )
                    {
                        throw new IOException( String.format( "Invalid header data, versionLength=%d, versionSpace=%d.",
                                                              versionLength, versionSpace ) );
                    }
                    storeFormatVersion = new byte[versionLength];
                    page.getBytes( storeFormatVersion );
                    for ( int i = versionSpace - versionLength; i-- > 0; )
                    {
                        if ( page.getByte() != 0 )
                        {
                            throw new IOException( "Unexpected header data." );
                        }
                    }
                } while ( page.shouldRetry() );
                return new CountsStoreHeader( storeFormatVersion, dataRecords, lastTxId );
            }
            else
            {
                throw new IOException( "Could not read count store header page" );
            }
        }
    }

    void write( PagedFile pagedFile ) throws IOException
    {
        try ( PageCursor page = pagedFile.io( 0, PF_EXCLUSIVE_LOCK ) )
        {
            if ( page.next() )
            {
                write( page );
            }
            else
            {
                throw new IOException( "Could not write count store header page" );
            }
        }
    }

    void write( PageCursor page ) throws IOException
    {
        do
        {
            page.setOffset( 0 );
            page.putShort( (short) headerRecords() );
            page.putShort( (short) storeFormatVersion.length );
            page.putInt( dataRecords );
            page.putLong( lastTxId );
            page.putBytes( storeFormatVersion );
            page.setOffset( RECORD_SIZE * headerRecords() );
        } while ( page.shouldRetry() );
    }
}
