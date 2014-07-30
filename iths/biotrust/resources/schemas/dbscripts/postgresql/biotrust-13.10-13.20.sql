/*
 * Copyright (c) 2013 LabKey Corporation
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

/* biotrust-13.10-13.11.sql */

-- add LockedState column to RequestStatus table
ALTER TABLE biotrust.RequestStatus ADD COLUMN LockedState BOOLEAN NOT NULL DEFAULT FALSE;

/* biotrust-13.11-13.12.sql */

-- add ApprovalState column to RequestStatus table
ALTER TABLE biotrust.RequestStatus ADD COLUMN ApprovalState BOOLEAN NOT NULL DEFAULT FALSE;