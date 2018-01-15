/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.backend.store.cassandra;

import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;

import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.backend.store.BackendEntryIterator;
import com.baidu.hugegraph.type.HugeType;
import com.baidu.hugegraph.util.E;
import com.datastax.driver.core.ColumnDefinitions.Definition;
import com.datastax.driver.core.Row;

public class CassandraEntryIterator extends BackendEntryIterator<Row> {

    private final Iterator<Row> rows;
    private final BiFunction<BackendEntry, BackendEntry, BackendEntry> merger;

    private BackendEntry next;

    public CassandraEntryIterator(Iterator<Row> rows, Query query,
           BiFunction<BackendEntry, BackendEntry, BackendEntry> merger) {
        super(query);
        this.rows = rows;
        this.merger = merger;
        this.next = null;
    }

    @Override
    protected final boolean fetch() {
        assert this.current == null;
        if (this.next != null) {
            this.current = this.next;
            this.next = null;
        }

        while (this.rows.hasNext()) {
            CassandraBackendEntry e = this.row2Entry(this.rows.next());
            BackendEntry merged = this.merger.apply(this.current, e);
            if (this.current == null) {
                // The first time to read
                this.current = merged;
            } else if (merged == this.current) {
                // Does the next entry belongs to the current entry
                assert merged != null;
            } else {
                // New entry
                assert this.next == null;
                this.next = merged;
                return true;
            }
        }
        return this.current != null;
    }

    @Override
    protected final long sizeOf(BackendEntry entry) {
        CassandraBackendEntry e = (CassandraBackendEntry) entry;
        int subRowsSize = e.subRows().size();
        return subRowsSize > 0 ? subRowsSize : 1L;
    }

    @Override
    protected final long skip(BackendEntry entry, long skip) {
        CassandraBackendEntry e = (CassandraBackendEntry) entry;
        E.checkState(e.subRows().size() > skip, "Invalid entry to skip");
        for (long i = 0; i < skip; i++) {
            e.subRows().remove(0);
        }
        return e.subRows().size();
    }

    private CassandraBackendEntry row2Entry(Row row) {
        HugeType type = this.query.resultType();
        CassandraBackendEntry entry = new CassandraBackendEntry(type);

        List<Definition> cols = row.getColumnDefinitions().asList();
        for (Definition col : cols) {
            String name = col.getName();
            Object value = row.getObject(name);
            entry.column(CassandraTable.parseKey(name), value);
        }

        return entry;
    }
}