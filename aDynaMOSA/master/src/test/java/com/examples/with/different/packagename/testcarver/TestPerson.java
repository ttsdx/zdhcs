/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
/*
 * This file was automatically generated by EvoSuite
 */

package com.examples.with.different.packagename.testcarver;

import org.junit.Test;
//import org.junit.runner.RunWith;
//import org.evosuite.junit.EvoSuiteRunner;
import static org.junit.Assert.*;
import com.examples.with.different.packagename.testcarver.Person;
import org.junit.BeforeClass;

//@RunWith(EvoSuiteRunner.class)
public class TestPerson {

//	@BeforeClass 
//	public static void initEvoSuiteFramework(){ 
//		org.evosuite.Properties.REPLACE_CALLS = true; 
//	} 



	@Test
	public void test0_1() throws Throwable {
		Person person0 = new Person("", "");
		String string0 = person0.getFirstName();
		assertEquals("", string0);
	}



	@Test
	public void test0() throws Throwable {
		Person person0 = new Person("", "");
		String string0 = person0.getLastName();
		assertEquals("", string0);
	}


}
