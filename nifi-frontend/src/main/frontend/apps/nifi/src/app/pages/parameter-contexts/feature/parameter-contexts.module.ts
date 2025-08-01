/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { StoreModule } from '@ngrx/store';
import { EffectsModule } from '@ngrx/effects';
import { ParameterContexts } from './parameter-contexts.component';
import { ParameterContextsRoutingModule } from './parameter-contexts-routing.module';
import { parameterContextsFeatureKey, reducers } from '../state';
import { ParameterContextListingEffects } from '../state/parameter-context-listing/parameter-context-listing.effects';
import { Navigation } from '../../../ui/common/navigation/navigation.component';
import { BannerText } from '../../../ui/common/banner-text/banner-text.component';
import { ParameterContextListing } from '../ui/parameter-context-listing/parameter-context-listing.component';
import { ProcessGroupReferences } from '../ui/parameter-context-listing/process-group-references/process-group-references.component';

@NgModule({
    declarations: [ParameterContexts],
    exports: [ParameterContexts],
    imports: [
        CommonModule,
        ParameterContextsRoutingModule,
        StoreModule.forFeature(parameterContextsFeatureKey, reducers),
        EffectsModule.forFeature(ParameterContextListingEffects),
        ParameterContextListing,
        Navigation,
        BannerText,
        ProcessGroupReferences
    ]
})
export class ParameterContextsModule {}
