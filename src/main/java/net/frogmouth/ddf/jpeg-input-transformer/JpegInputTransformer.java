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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.Serializable;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.Date;

import javax.xml.bind.DatatypeConverter;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.jpeg.JpegProcessingException;
import com.drew.imaging.jpeg.JpegSegmentReader;
import com.drew.lang.ByteArrayReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.Metadata;
import com.drew.metadata.iptc.IptcReader;

import ddf.catalog.data.AttributeDescriptor;
import ddf.catalog.data.AttributeType.AttributeFormat;
import ddf.catalog.data.BasicTypes;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.MetacardImpl;
// import ddf.catalog.data.MetacardType;
import ddf.catalog.data.QualifiedMetacardType;
import ddf.catalog.data.MetacardTypeRegistry;
import ddf.catalog.CatalogFramework;
import ddf.catalog.transform.CatalogTransformerException;
import ddf.catalog.transform.InputTransformer;
import ddf.geo.formatter.CompositeGeometry;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.PrecisionModel;

/**
 * Converts JPEG images (with EXIF/XML metadata) into a Metacard.
 * 
 * @author Brad Hards
 * @author bradh@frogmouth.net
 * @since 2.2.0
 */
public class JpegInputTransformer implements InputTransformer {

	private static final String METACARD_TYPE_PROPERTY_KEY = "metacard-type";
	private static final String ID = "jpeg";
	private static final String MIME_TYPE = "image/jpeg";
	private static final String SOURCE_ID_PROPERTY = "source-id";
	
//	private MetacardTypeRegistry mTypeRegistry;

// 	public static final SimpleDateFormat ISO_8601_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
// 	static {
// 		ISO_8601_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
// 	}

	private static final Logger LOGGER = Logger.getLogger(JpegInputTransformer.class);
	private CatalogFramework mCatalog;
//	public JpegInputTransformer(MetacardTypeRegistry mTypeRegistry){
//	    this.mTypeRegistry = mTypeRegistry;
//	}
	
	/**
	 * Transforms JPEG images with EXIF or XMP metadata into a {@link Metacard}
	 */
	@Override
	public Metacard transform(InputStream input) throws IOException, CatalogTransformerException {
		return transform(input, null);
	}

	@Override
	public Metacard transform(InputStream input, String uri) throws IOException, CatalogTransformerException {

		if (input == null) {
			throw new CatalogTransformerException("Cannot transform null input.");
		}

		MetacardImpl metacard = new MetacardImpl(BasicTypes.BASIC_METACARD);
		try {
			JpegSegmentReader segmentReader = new JpegSegmentReader(input, true);
			byte[] exifSegment = segmentReader.readSegment(JpegSegmentReader.SEGMENT_APP1);
			byte[] iptcSegment = segmentReader.readSegment(JpegSegmentReader.SEGMENT_APPD);
			Metadata metadata = new Metadata();
			if (exifSegment != null) {
				new ExifReader().extract(new ByteArrayReader(exifSegment), metadata);
			}
			if (iptcSegment != null) {
				new IptcReader().extract(new ByteArrayReader(iptcSegment), metadata);
			}

			ExifSubIFDDirectory directory;
			metadata.getDirectory(ExifSubIFDDirectory.class);
			directory = metadata.getDirectory(ExifSubIFDDirectory.class);
			Date date = directory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
			LOGGER.info(date);
			metacard.setCreatedDate(date);

			GpsDirectory gpsDirectory = metadata.getDirectory(GpsDirectory.class);
			if (gpsDirectory.getGeoLocation() != null) {
				GeoLocation location = gpsDirectory.getGeoLocation();

				GeometryFactory geomFactory = new GeometryFactory(new PrecisionModel(com.vividsolutions.jts.geom.PrecisionModel.FLOATING), 4326);
				Geometry point = geomFactory.createPoint(new Coordinate(location.getLongitude(), location.getLatitude()));
				CompositeGeometry position = CompositeGeometry.getCompositeGeometry(point);
				metacard.setLocation(position.toWkt());
			}

			if (uri != null) {
				metacard.setResourceURI(URI.create(uri));
			} else {
				metacard.setResourceURI(null);
			}
		} catch (JpegProcessingException e) {
			LOGGER.error(e);
			throw new CatalogTransformerException(e);
		}

		return metacard;
	}

	@Override
	public String toString() {
		return "InputTransformer {Impl=" + this.getClass().getName() + ", id=" + ID + ", mime-type=" + MIME_TYPE + "}";
	}

	public void setCatalog(CatalogFramework catalog) {
	    this.mCatalog = catalog;
	}

}
