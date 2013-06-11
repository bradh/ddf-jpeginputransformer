/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version. 
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
package net.frogmouth.ddf.jpeginputtransformer;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertNotNull;

import static org.mockito.Mockito.*;



import org.apache.commons.io.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.List;

import org.junit.Test;
import org.osgi.framework.BundleContext;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;

import ddf.catalog.data.Metacard;
import ddf.catalog.data.QualifiedMetacardType;
import ddf.catalog.data.MetacardTypeRegistry;
import ddf.catalog.transform.CatalogTransformerException;
import ddf.catalog.source.UnsupportedQueryException;
import ddf.catalog.source.SourceUnavailableException;
import ddf.catalog.federation.FederationException;
import ddf.catalog.operation.*;

public class TestJpegInputTransformer {
	private static final BundleContext context = mock(BundleContext.class);
	private static List<QualifiedMetacardType> qmtList = new ArrayList<QualifiedMetacardType>();

	private static final String TEST_DATA_PATH = "src/test/resources/";

	public static JpegInputTransformer createTransformer() throws UnsupportedQueryException, SourceUnavailableException, FederationException {
		JpegInputTransformer transformer = new JpegInputTransformer();
		ddf.catalog.CatalogFramework catalog = mock(ddf.catalog.CatalogFramework.class);
		when(catalog.query(any(QueryRequest.class))).thenReturn(new QueryResponseImpl(null, "sourceId"));
		transformer.setCatalog(catalog);

		return transformer;
	}

	@Test(expected = CatalogTransformerException.class)
	public void testNullInput() throws IOException, CatalogTransformerException, UnsupportedQueryException, SourceUnavailableException, FederationException {
		createTransformer().transform(null);
	}

	@Test(expected = CatalogTransformerException.class)
	public void testBadInput() throws IOException, CatalogTransformerException, UnsupportedQueryException, SourceUnavailableException, FederationException {
		createTransformer().transform(new ByteArrayInputStream("{key=".getBytes()));
	}

	@Test()
	public void testIPhone() throws IOException, CatalogTransformerException, UnsupportedQueryException, SourceUnavailableException, FederationException, ParseException  {
		File file = new File(TEST_DATA_PATH + "Apple iPhone 4.jpg");
		FileInputStream fis = FileUtils.openInputStream(file);
		Metacard metacard = createTransformer().transform(fis);

		assertNotNull(metacard);

		assertNotNull(metacard.getCreatedDate());
		assertThat(metacard.getCreatedDate().getYear() + 1900, is(2011));
		assertThat(metacard.getCreatedDate().getMonth() + 1, is(1));
		assertThat(metacard.getCreatedDate().getDate(), is(13));
		assertThat(metacard.getCreatedDate().getHours(), is(14));
		assertThat(metacard.getCreatedDate().getMinutes(), is(33));
		assertThat(metacard.getCreatedDate().getSeconds(), is(39));

		WKTReader reader = new WKTReader();
		Geometry geometry = reader.read(metacard.getLocation());
		assertEquals(12.488833, geometry.getCoordinate().x, 0.00001);
		assertEquals(41.853, geometry.getCoordinate().y, 0.00001);
	}

	@Test()
	public void testSonyDSCHXV5() throws IOException, CatalogTransformerException, UnsupportedQueryException, SourceUnavailableException, FederationException, ParseException  {
		File file = new File(TEST_DATA_PATH + "Sony DSC-HX5V.jpg");
		FileInputStream fis = FileUtils.openInputStream(file);
		Metacard metacard = createTransformer().transform(fis);

		assertNotNull(metacard);

		assertNotNull(metacard.getCreatedDate());
		assertThat(metacard.getCreatedDate().getYear() + 1900, is(2010));
		assertThat(metacard.getCreatedDate().getMonth() + 1, is(7));
		assertThat(metacard.getCreatedDate().getDate(), is(14));
		assertThat(metacard.getCreatedDate().getHours(), is(11));
		assertThat(metacard.getCreatedDate().getMinutes(), is(00));
		assertThat(metacard.getCreatedDate().getSeconds(), is(23));

		WKTReader reader = new WKTReader();
		Geometry geometry = reader.read(metacard.getLocation());
		assertEquals(-104.303846389, geometry.getCoordinate().x, 0.00001);
		assertEquals(39.5698783333, geometry.getCoordinate().y, 0.00001);

		byte[] thumbnail = metacard.getThumbnail();
		assertNotNull(thumbnail);
		assertThat(thumbnail.length, is(11490));
	}
}
