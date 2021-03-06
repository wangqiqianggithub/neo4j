/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.bolt.v1.messaging;

import java.io.IOException;
import java.util.Map;

import org.neo4j.bolt.logging.BoltMessageLogger;
import org.neo4j.bolt.runtime.BoltConnection;
import org.neo4j.bolt.runtime.BoltResult;
import org.neo4j.bolt.runtime.Neo4jError;
import org.neo4j.cypher.result.QueryResult;
import org.neo4j.logging.Log;
import org.neo4j.values.AnyValue;
import org.neo4j.values.virtual.MapValue;

/**
 * This class is responsible for routing incoming request messages to a worker
 * as well as handling outgoing response messages via appropriate handlers.
 */
public class BoltMessageRouter implements BoltRequestMessageHandler
{
    private final BoltMessageLogger messageLogger;

    // Note that these callbacks can be used for multiple in-flight requests simultaneously, you cannot reset them
    // while there are in-flight requests.
    private final MessageProcessingHandler initHandler;
    private final MessageProcessingHandler runHandler;
    private final MessageProcessingHandler resultHandler;
    private final MessageProcessingHandler defaultHandler;

    private final BoltConnection connection;

    public BoltMessageRouter( Log internalLog, BoltMessageLogger messageLogger,
                              BoltConnection connection, BoltResponseMessageHandler<IOException> output )
    {
        this.messageLogger = messageLogger;

        this.initHandler = new InitHandler( output, connection, internalLog );
        this.runHandler = new RunHandler( output, connection, internalLog );
        this.resultHandler = new ResultHandler( output, connection, internalLog );
        this.defaultHandler = new MessageProcessingHandler( output, connection, internalLog );

        this.connection = connection;
    }

    @Override
    public void onInit( String userAgent, Map<String,Object> authToken )
    {
        messageLogger.logInit(userAgent );
        Init message = new Init( userAgent, authToken );
        connection.enqueue( stateMachine -> stateMachine.process( message, initHandler ) );
    }

    @Override
    public void onAckFailure()
    {
        messageLogger.logAckFailure();
        connection.enqueue( stateMachine -> stateMachine.process( AckFailure.INSTANCE, defaultHandler ) );
    }

    @Override
    public void onReset()
    {
        messageLogger.clientEvent("INTERRUPT");
        messageLogger.logReset();
        connection.interrupt();
        connection.enqueue( stateMachine -> stateMachine.process( Reset.INSTANCE, defaultHandler ) );
    }

    @Override
    public void onRun( String statement, MapValue params )
    {
        messageLogger.logRun();
        Run message = new Run( statement, params );
        connection.enqueue( stateMachine -> stateMachine.process( message, runHandler ) );
    }

    @Override
    public void onExternalError( Neo4jError error )
    {
        messageLogger.clientEvent( "ERROR", error::message );
        connection.enqueue( stateMachine -> stateMachine.handleExternalFailure( error, defaultHandler ) );
    }

    @Override
    public void onDiscardAll()
    {
        messageLogger.logDiscardAll();
        connection.enqueue( stateMachine -> stateMachine.process( DiscardAll.INSTANCE, resultHandler ) );
    }

    @Override
    public void onPullAll()
    {
        messageLogger.logPullAll();
        connection.enqueue( stateMachine -> stateMachine.process( PullAll.INSTANCE, resultHandler ) );
    }

    private static class InitHandler extends MessageProcessingHandler
    {
        InitHandler( BoltResponseMessageHandler<IOException> handler, BoltConnection connection, Log log )
        {
            super( handler, connection, log );
        }

    }

    private static class RunHandler extends MessageProcessingHandler
    {
        RunHandler( BoltResponseMessageHandler<IOException> handler, BoltConnection connection, Log log )
        {
            super( handler, connection, log );
        }

    }

    private static class ResultHandler extends MessageProcessingHandler
    {
        ResultHandler( BoltResponseMessageHandler<IOException> handler, BoltConnection connection,
                Log log )
        {
            super( handler, connection, log );
        }

        @Override
        public void onRecords( final BoltResult result, final boolean pull ) throws Exception
        {
            result.accept( new BoltResult.Visitor()
            {
                @Override
                public void visit( QueryResult.Record record ) throws Exception
                {
                    if ( pull )
                    {
                        handler.onRecord( record );
                    }
                }

                @Override
                public void addMetadata( String key, AnyValue value )
                {
                    metadata.add( key, value );
                }

            } );
        }

    }
}
