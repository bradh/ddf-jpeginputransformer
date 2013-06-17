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
import java.io.StringWriter;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.Date;

import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;

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
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.Metadata;
import com.drew.metadata.iptc.IptcReader;

import ddf.catalog.data.AttributeDescriptor;
import ddf.catalog.data.AttributeType.AttributeFormat;
import ddf.catalog.data.BasicTypes;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.MetacardImpl;
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

import gov.loc.mix.v20.BasicDigitalObjectInformationType;
import gov.loc.mix.v20.ImageCaptureMetadataType;
import gov.loc.mix.v20.Mix;
import gov.loc.mix.v20.StringType;

/**
 * Converts JPEG images (with EXIF/XML metadata) into a Metacard.
 * 
 * @author Brad Hards
 * @author bradh@frogmouth.net
 * @since DDF 2.2.0
 */
public class JpegInputTransformer implements InputTransformer {

	private static final String METACARD_TYPE_PROPERTY_KEY = "metacard-type";
	private static final String ID = "jpeg";
	private static final String MIME_TYPE = "image/jpeg";
	
	private static final Logger LOGGER = Logger.getLogger(JpegInputTransformer.class);
	private CatalogFramework mCatalog;
	private MetacardImpl mMetacard;
	
	/**
	 * Transforms JPEG images with EXIF or XMP metadata into a {@link Metacard}
	 */
	@Override
	public Metacard transform(InputStream input) throws IOException, CatalogTransformerException {
		return transform(input, null);
	}

	@Override
	public Metacard transform(InputStream input, String id) throws IOException, CatalogTransformerException {

		if (input == null) {
			throw new CatalogTransformerException("Cannot transform null input.");
		}

		mMetacard = new MetacardImpl(BasicTypes.BASIC_METACARD);
		try {
			Metadata metadata = extractImageMetadata(input);

			processExifSubIFDDirectory(metadata);

			generateThumbnail(metadata);

			processGPSDirectory(metadata);

			if (id != null) {
				mMetacard.setId(id);
			} else {
				mMetacard.setId(null);
			}

			mMetacard.setContentTypeName(MIME_TYPE);

			// TODO: this should produce a real name
			mMetacard.setTitle("(Unnamed JPEG image)");

			convertImageMetadataToMetacardMetadata(metadata);
		} catch (JpegProcessingException e) {
			LOGGER.error(e);
			throw new CatalogTransformerException(e);
		} catch (JAXBException e) {
			LOGGER.error(e);
			throw new CatalogTransformerException(e);
		}    

		return mMetacard;
	}

	@Override
	public String toString() {
		return "InputTransformer {Impl=" + this.getClass().getName() + ", id=" + ID + ", mime-type=" + MIME_TYPE + "}";
	}

	public void setCatalog(CatalogFramework catalog) {
	    this.mCatalog = catalog;
	}

	private Metadata extractImageMetadata(InputStream input) throws JpegProcessingException {
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
		return metadata;
	}

	private void processExifSubIFDDirectory(Metadata metadata) {
		ExifSubIFDDirectory exifdirectory = metadata.getDirectory(ExifSubIFDDirectory.class);
		if (exifdirectory != null)
		{
			Date date = exifdirectory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
			mMetacard.setCreatedDate(date);
		}
	}

	private void generateThumbnail(Metadata metadata) {
		ExifThumbnailDirectory thumbnailDirectory = metadata.getDirectory(ExifThumbnailDirectory.class);
		if ((thumbnailDirectory != null) && (thumbnailDirectory.hasThumbnailData())) {
			mMetacard.setThumbnail(thumbnailDirectory.getThumbnailData());
		}
		// Future: we could generate a thumbnail from the source image if we don't have one here.
	}

	private void processGPSDirectory(Metadata metadata) {
		GpsDirectory gpsDirectory = metadata.getDirectory(GpsDirectory.class);
		if ((gpsDirectory != null) && (gpsDirectory.getGeoLocation() != null)) {
			GeoLocation location = gpsDirectory.getGeoLocation();

			GeometryFactory geomFactory = new GeometryFactory(new PrecisionModel(com.vividsolutions.jts.geom.PrecisionModel.FLOATING), 4326);
			Geometry point = geomFactory.createPoint(new Coordinate(location.getLongitude(), location.getLatitude()));
			CompositeGeometry position = CompositeGeometry.getCompositeGeometry(point);
			mMetacard.setLocation(position.toWkt());
		}
	}

	private void convertImageMetadataToMetacardMetadata(Metadata metadata) throws JAXBException {
		// TODO: use this element to hold an XML mapping (schema TBA) of the rest of the metadata
		Mix mixMetadata = new Mix();
		BasicDigitalObjectInformationType basicDigitalObjectInformation = new BasicDigitalObjectInformationType();

		ImageCaptureMetadataType imageCaptureMetadataType = createImageCaptureMetadata(metadata);
		mixMetadata.setImageCaptureMetadata(imageCaptureMetadataType);

		BasicDigitalObjectInformationType.FormatDesignation formatDesignation = new BasicDigitalObjectInformationType.FormatDesignation();
		formatDesignation.setFormatName(stringToStringType(MIME_TYPE)); // Future: see if we can read this from the metadata
		basicDigitalObjectInformation.setFormatDesignation(formatDesignation);

		// Test hack:
		gov.loc.mix.v20.NonNegativeIntegerType dummyFileSize = new gov.loc.mix.v20.NonNegativeIntegerType();
		dummyFileSize.setValue(new java.math.BigInteger("10000")); // FIXME: this should be actual data
		basicDigitalObjectInformation.setFileSize(dummyFileSize);

		mixMetadata.setBasicDigitalObjectInformation(basicDigitalObjectInformation);

		StringWriter writer = new StringWriter();
		JAXBContext context = JAXBContext.newInstance(Mix.class);
		Marshaller m = context.createMarshaller();
		m.marshal(new JAXBElement(new QName(Mix.class.getSimpleName()), Mix.class, mixMetadata), writer);
		mMetacard.setMetadata(writer.toString());
	}

	private ImageCaptureMetadataType createImageCaptureMetadata(Metadata metadata) throws JAXBException {
		ImageCaptureMetadataType imageCaptureMetadataType = new ImageCaptureMetadataType();
		ExifIFD0Directory exifdirectory = metadata.getDirectory(ExifIFD0Directory.class);

		ImageCaptureMetadataType.DigitalCameraCapture digitalCameraCapture = new ImageCaptureMetadataType.DigitalCameraCapture();
		digitalCameraCapture.setDigitalCameraManufacturer(stringToStringType(exifdirectory.getString(ExifIFD0Directory.TAG_MAKE)));
		imageCaptureMetadataType.setDigitalCameraCapture(digitalCameraCapture);
		return imageCaptureMetadataType;
	}

	// Convenience API for creating a MIX v2.0 StringType when we have a plain Java String
	private StringType stringToStringType(String string) {
		StringType st = new StringType();
		st.setValue(string);
		return st;
	}
}
