/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hedwig.util;

public class Either<T, U> {

    private T x;
    private U y;

    private Either(T x, U y) {
        this.x = x;
        this.y = y;
    }

    public static <T, U> Either<T, U> of(T x, U y) {
        return new Either<T, U>(x, y);
    }

    public static <T, U> Either<T, U> left(T x) {
        return new Either<T, U>(x, null);
    }

    public static <T, U> Either<T, U> right(U y) {
        return new Either<T, U>(null, y);
    }

    public T left() {
        return x;
    }

    public U right() {
        return y;
    }

}
