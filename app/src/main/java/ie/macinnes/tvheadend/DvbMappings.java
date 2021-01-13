/*
 * Copyright (c) 2017 Kiall Mac Innes <kiall@macinnes.ie>
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

package ie.macinnes.tvheadend;

import android.util.SparseArray;

import androidx.tvprovider.media.tv.TvContractCompat;

public class DvbMappings {

    public static final SparseArray<String> PROGRAM_GENRE = new SparseArray<String>() {
        {
            append(16, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MOVIES));
            append(17, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MOVIES));
            append(18, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MOVIES));
            append(19, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MOVIES));
            append(20, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.COMEDY));
            append(21, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ENTERTAINMENT));
            append(22, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MOVIES));
            append(23, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.DRAMA));
            append(32, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.NEWS));
            append(33, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.NEWS));
            append(34, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.NEWS));
            append(35, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.TECH_SCIENCE));
            append(48, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ENTERTAINMENT));
            append(49, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ENTERTAINMENT));
            append(50, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ENTERTAINMENT));
            append(51, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ENTERTAINMENT));
            append(64, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(65, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(66, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(67, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(68, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(69, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(70, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(71, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(72, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(73, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(74, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(75, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SPORTS));
            append(80, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.FAMILY_KIDS));
            append(81, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.FAMILY_KIDS));
            append(82, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.FAMILY_KIDS));
            append(82, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.FAMILY_KIDS));
            append(83, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.FAMILY_KIDS));
            append(84, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.FAMILY_KIDS));
            append(85, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.FAMILY_KIDS));
            append(96, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MUSIC));
            append(97, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MUSIC));
            append(98, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MUSIC));
            append(99, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MUSIC));
            append(100, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MUSIC));
            append(101, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MUSIC));
            append(102, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MUSIC));
            append(112, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ARTS));
            append(113, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ARTS));
            append(114, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ARTS));
            append(115, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ARTS));
            append(116, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ARTS));
            append(117, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ARTS));
            append(118, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MOVIES));
            append(118, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.MOVIES));
            append(120, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.NEWS));
            append(121, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.NEWS));
            append(122, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ARTS));
            append(129, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.TECH_SCIENCE));
            append(144, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.TECH_SCIENCE));
            append(145, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ANIMAL_WILDLIFE));
            append(146, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.TECH_SCIENCE));
            append(147, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.TECH_SCIENCE));
            append(148, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.TECH_SCIENCE));
            append(150, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.EDUCATION));
            append(160, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.LIFE_STYLE));
            append(161, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.TRAVEL));
            append(162, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.ARTS));
            append(163, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.LIFE_STYLE));
            append(164, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.LIFE_STYLE));
            append(165, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.LIFE_STYLE));
            append(166, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.SHOPPING));
            append(167, TvContractCompat.Programs.Genres.encode(TvContractCompat.Programs.Genres.LIFE_STYLE));
        }
    };

    private DvbMappings() {
    }
}
