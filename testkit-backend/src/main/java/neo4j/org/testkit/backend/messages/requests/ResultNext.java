/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package neo4j.org.testkit.backend.messages.requests;

import lombok.Getter;
import lombok.Setter;
import neo4j.org.testkit.backend.TestkitState;
import neo4j.org.testkit.backend.messages.responses.NullRecord;
import neo4j.org.testkit.backend.messages.responses.Record;
import neo4j.org.testkit.backend.messages.responses.TestkitResponse;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import org.neo4j.driver.Result;
import org.neo4j.driver.exceptions.NoSuchRecordException;

@Setter
@Getter
public class ResultNext implements TestkitRequest
{
    private ResultNextBody data;

    @Override
    public TestkitResponse process( TestkitState testkitState )
    {
        try
        {
            Result result = testkitState.getResults().get( data.getResultId() );
            return createResponse( result.next() );
        }
        catch ( NoSuchRecordException ignored )
        {
            return NullRecord.builder().build();
        }
    }

    @Override
    public CompletionStage<Optional<TestkitResponse>> processAsync( TestkitState testkitState )
    {
        return testkitState.getResultCursors().get( data.getResultId() )
                           .nextAsync()
                           .thenApply( record -> record != null ? createResponse( record ) : NullRecord.builder().build() )
                           .thenApply( Optional::of );
    }

    private Record createResponse( org.neo4j.driver.Record record )
    {
        return Record.builder().data( Record.RecordBody.builder().values( record ).build() ).build();
    }

    @Setter
    @Getter
    public static class ResultNextBody
    {
        private String resultId;
    }
}
