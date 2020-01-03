/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.webtide.demo.auction;

import java.text.DecimalFormat;
import java.text.NumberFormat;

public class Utils {

    private static final NumberFormat cashAmount = new DecimalFormat("$#,##0.00");

    public static String formatCurrency(double amount) {
        return cashAmount.format(amount);
    }
}
