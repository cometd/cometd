/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.cometd.tests.spring.oort;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.cometd.annotation.Service;
import org.cometd.oort.Seti;

@Singleton
@Named
@Service
public class OortService {
    public final Seti seti;

    @Inject
    public OortService(Seti seti) {
        this.seti = seti;
    }
}
