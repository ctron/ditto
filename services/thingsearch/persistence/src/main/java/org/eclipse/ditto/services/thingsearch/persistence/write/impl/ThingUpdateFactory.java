/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.thingsearch.persistence.write.impl;

import static org.eclipse.ditto.services.thingsearch.persistence.PersistenceConstants.FIELD_DELETED;
import static org.eclipse.ditto.services.thingsearch.persistence.PersistenceConstants.FIELD_DELETED_FLAG;
import static org.eclipse.ditto.services.thingsearch.persistence.PersistenceConstants.SET;
import static org.eclipse.ditto.services.thingsearch.persistence.PersistenceConstants.UNSET;

import java.util.Date;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.services.thingsearch.persistence.mapping.ThingDocumentMapper;
import org.eclipse.ditto.services.thingsearch.persistence.write.IndexLengthRestrictionEnforcer;

/**
 * Factory to create updates on thing level.
 */
final class ThingUpdateFactory {

    private ThingUpdateFactory() {
        throw new AssertionError();
    }

    /**
     * Creates an update to delete a whole thing.
     *
     * @return the update Bson
     */
    static Bson createDeleteThingUpdate() {
        final Document delete = new Document()
                .append(FIELD_DELETED, new Date())
                .append(FIELD_DELETED_FLAG, true);

        return new Document(SET, delete);
    }

    /**
     * Creates an update to update a whole thing.
     *
     * @param indexLengthRestrictionEnforcer the restriction helper to enforce size restrictions.
     * @param thing the thing to be set
     * @return the update Bson
     */
    static Bson createUpdateThingUpdate(final IndexLengthRestrictionEnforcer indexLengthRestrictionEnforcer,
            final Thing thing) {
        return toUpdate(ThingDocumentMapper.toDocument(indexLengthRestrictionEnforcer.enforceRestrictions(thing)));
    }

    private static Document toUpdate(final Document document) {
        document.put(FIELD_DELETED_FLAG, false);
        return new Document(SET, document).append(UNSET, new Document(FIELD_DELETED, 1));
    }

}
