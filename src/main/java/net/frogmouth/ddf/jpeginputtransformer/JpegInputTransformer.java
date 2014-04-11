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
import java.lang.Object;
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
import com.drew.metadata.Directory;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.iptc.IptcReader;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.Tag;

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

import com.adobe.xmp.XMPConst;
import com.adobe.xmp.XMPMeta;
import com.adobe.xmp.XMPMetaFactory;
import com.adobe.xmp.XMPSchemaRegistry;
import com.adobe.xmp.options.SerializeOptions;

enum datatype { dtString, dtInt, dtRational };

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
	private static final String NS_EXIF_EX = "http://cipa.jp/exif/1.0/";

	private static final Logger LOGGER = Logger.getLogger(JpegInputTransformer.class);
	private CatalogFramework mCatalog;

	private static XMPSchemaRegistry registry = XMPMetaFactory.getSchemaRegistry();
	
	protected abstract static class XMPMapping {
		int _ExifTag;
		String _XmpNamespace;
		String _XmpName;
		datatype _dataType;

		protected XMPMapping(int ExifTag, String XmpNamespace, String XmpName) {
			_ExifTag = ExifTag;
			_XmpNamespace = XmpNamespace;
			_XmpName = XmpName;
		}
		
		abstract void addTagValueToXmp(XMPMeta xmpMeta, Directory directoryToReadFrom) throws com.adobe.xmp.XMPException, com.drew.metadata.MetadataException;
		
		protected int exifTag() {
			return _ExifTag;
		}
	}
	
	private static class XMPMappingString extends XMPMapping {
		XMPMappingString(int ExifTag, String XmpNamespace, String XmpName) {
			super(ExifTag, XmpNamespace, XmpName);
		}
		
		void addTagValueToXmp(XMPMeta xmpMeta, Directory directoryToReadFrom) throws com.adobe.xmp.XMPException {
			xmpMeta.setProperty(_XmpNamespace, _XmpName, directoryToReadFrom.getString(_ExifTag));
		}
	}

	private static class XMPMappingInt extends XMPMapping {
		XMPMappingInt(int ExifTag, String XmpNamespace, String XmpName) {
			super(ExifTag, XmpNamespace, XmpName);
		}
		
		void addTagValueToXmp(XMPMeta xmpMeta, Directory directoryToReadFrom) throws com.adobe.xmp.XMPException, com.drew.metadata.MetadataException {
			xmpMeta.setProperty(_XmpNamespace, _XmpName, directoryToReadFrom.getInt(_ExifTag));
		}
	}
	
	private static class XMPMappingRational extends XMPMapping {
		XMPMappingRational(int ExifTag, String XmpNamespace, String XmpName) {
			super(ExifTag, XmpNamespace, XmpName);
		}
		
		void addTagValueToXmp(XMPMeta xmpMeta, Directory directoryToReadFrom) throws com.adobe.xmp.XMPException {
			xmpMeta.setProperty(_XmpNamespace, _XmpName, directoryToReadFrom.getRational(_ExifTag));
		}
	}
	
	private static class XMPMappingDateTime extends XMPMapping {
		XMPMappingDateTime(int ExifTag, String XmpNamespace, String XmpName) {
			super(ExifTag, XmpNamespace, XmpName);
		}
		
		void addTagValueToXmp(XMPMeta xmpMeta, Directory directoryToReadFrom) throws com.adobe.xmp.XMPException {
			xmpMeta.setProperty(_XmpNamespace, _XmpName, directoryToReadFrom.getDate(_ExifTag));
		}
	}

	private static XMPMapping[] xmpmapSubIFD = {
		new XMPMappingInt(ExifSubIFDDirectory.TAG_SENSING_METHOD, XMPConst.NS_EXIF, "SensingMethod"),
		new XMPMappingRational(ExifSubIFDDirectory.TAG_FNUMBER, XMPConst.NS_EXIF, "FNumber"),
		new XMPMappingRational(ExifSubIFDDirectory.TAG_EXPOSURE_TIME, XMPConst.NS_EXIF, "ExposureTime"),
		new XMPMappingRational(ExifSubIFDDirectory.TAG_EXPOSURE_PROGRAM, XMPConst.NS_EXIF, "ExposureProgram"),
		new XMPMappingInt(ExifSubIFDDirectory.TAG_SAMPLES_PER_PIXEL, XMPConst.NS_TIFF, "SamplesPerPixel"),
		new XMPMappingString(ExifSubIFDDirectory.TAG_EXIF_VERSION, XMPConst.NS_EXIF, "ExifVersion"),
		new XMPMappingString(ExifSubIFDDirectory.TAG_FLASHPIX_VERSION, XMPConst.NS_EXIF, "FlashpixVersion"),
		new XMPMappingInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH, XMPConst.NS_EXIF, "PixelXDimension"),
		new XMPMappingInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT, XMPConst.NS_EXIF, "PixelYDimension"),
		new XMPMappingDateTime(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, XMPConst.NS_EXIF, "DateTimeOriginal"),
		new XMPMappingDateTime(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED, XMPConst.NS_XMP, "CreateDate"),
		new XMPMappingString(ExifSubIFDDirectory.TAG_IMAGE_UNIQUE_ID, XMPConst.NS_EXIF, "ImageUniqueID"),
		new XMPMappingString(ExifSubIFDDirectory.TAG_CAMERA_OWNER_NAME, NS_EXIF_EX, "CameraOwnerName"),
	};
	
	private static XMPMapping[] xmpmapIFD0 = {
		new XMPMappingString(ExifIFD0Directory.TAG_MAKE, XMPConst.NS_TIFF, "Make"),
		new XMPMappingString(ExifIFD0Directory.TAG_MODEL, XMPConst.NS_TIFF, "Model"),
		new XMPMappingString(ExifIFD0Directory.TAG_IMAGE_DESCRIPTION, XMPConst.NS_DC, "description"),
		new XMPMappingString(ExifIFD0Directory.TAG_SOFTWARE, XMPConst.NS_XMP, "CreatorTool"),
		new XMPMappingString(ExifIFD0Directory.TAG_ARTIST, XMPConst.NS_DC, "creator"),
		new XMPMappingString(ExifIFD0Directory.TAG_COPYRIGHT, XMPConst.NS_DC, "rights"),
		new XMPMappingDateTime(ExifIFD0Directory.TAG_DATETIME, XMPConst.NS_XMP, "ModifyDate"),
	};
	
	private static XMPMapping[] xmpmapgps = {
		new XMPMappingString(GpsDirectory.TAG_GPS_VERSION_ID, XMPConst.NS_EXIF, "GPSVersionID")
	};

	private static XMPMapping[] xmpmapiptc = {
		new XMPMappingString(IptcDirectory.TAG_BY_LINE, XMPConst.NS_DC, "Creator"),
		new XMPMappingString(IptcDirectory.TAG_BY_LINE_TITLE, XMPConst.NS_PHOTOSHOP, "AuthorsPosition"),
	};

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

		MetacardImpl metacard = new MetacardImpl(BasicTypes.BASIC_METACARD);
		try {
			Metadata metadata = JpegMetadataReader.readMetadata(input);
		
			processExifSubIFDDirectory(metadata, metacard);

			generateThumbnail(metadata, metacard);

			processGPSDirectory(metadata, metacard);
			
			processIptcDirectory(metadata, metacard);

			if (id != null) {
				metacard.setId(id);
			} else {
				metacard.setId(null);
			}

			metacard.setContentTypeName(MIME_TYPE);

			convertImageMetadataToMetacardMetadata(metadata, metacard);
		} catch (JpegProcessingException e) {
			LOGGER.warn(e);
			throw new CatalogTransformerException(e);
		} catch (JAXBException e) {
			LOGGER.warn(e);
			throw new CatalogTransformerException(e);
		} catch (MetadataException e) {
			LOGGER.warn(e);
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

	private void processExifSubIFDDirectory(Metadata metadata, MetacardImpl metacard) {
		ExifSubIFDDirectory exifdirectory = metadata.getDirectory(ExifSubIFDDirectory.class);
		if (exifdirectory != null)
		{
			if (exifdirectory.containsTag(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)) {
				Date date = exifdirectory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
				metacard.setCreatedDate(date);
			}
		}
	}

	private void processIptcDirectory(Metadata metadata, MetacardImpl metacard) {
		IptcDirectory iptcdirectory = metadata.getDirectory(IptcDirectory.class);
		if (iptcdirectory != null)
		{
			if (iptcdirectory.containsTag(IptcDirectory.TAG_HEADLINE)) {
				String title = iptcdirectory.getString(IptcDirectory.TAG_HEADLINE);
				if (title.length() != 0)
				{
				      metacard.setTitle(title);
				}
			}

			else if (iptcdirectory.containsTag(IptcDirectory.TAG_CAPTION)) {
				String title = iptcdirectory.getString(IptcDirectory.TAG_CAPTION);
				metacard.setTitle(title);
			}
			// else?
		}
	}

	private void generateThumbnail(Metadata metadata, MetacardImpl metacard) {
		ExifThumbnailDirectory thumbnailDirectory = metadata.getDirectory(ExifThumbnailDirectory.class);
		if ((thumbnailDirectory != null) && (thumbnailDirectory.hasThumbnailData())) {
			metacard.setThumbnail(thumbnailDirectory.getThumbnailData());
		}
		// Future: we could generate a thumbnail from the source image if we don't have one here.
	}

	private void processGPSDirectory(Metadata metadata, MetacardImpl metacard) {
		GpsDirectory gpsDirectory = metadata.getDirectory(GpsDirectory.class);
		if ((gpsDirectory != null) && (gpsDirectory.getGeoLocation() != null)) {
			GeoLocation location = gpsDirectory.getGeoLocation();

			GeometryFactory geomFactory = new GeometryFactory(new PrecisionModel(com.vividsolutions.jts.geom.PrecisionModel.FLOATING), 4326);
			Geometry point = geomFactory.createPoint(new Coordinate(location.getLongitude(), location.getLatitude()));
			CompositeGeometry position = CompositeGeometry.getCompositeGeometry(point);
			metacard.setLocation(position.toWkt());
		}
	}

	private void convertImageMetadataToMetacardMetadata(Metadata metadata, MetacardImpl metacard) throws JAXBException, MetadataException, CatalogTransformerException {
		registry.getNamespaces();
		XMPMeta xmpMeta = XMPMetaFactory.create();
		try {
			ExifIFD0Directory exifdirectory = metadata.getDirectory(ExifIFD0Directory.class);
			if (exifdirectory != null) {
				for (Tag tag : exifdirectory.getTags()) {
					handleExifTag(exifdirectory, tag, xmpMeta);
				}
			}

			ExifSubIFDDirectory exifSubIFDDirectory = metadata.getDirectory(ExifSubIFDDirectory.class);
			if (exifSubIFDDirectory != null) {
				for (Tag tag : exifSubIFDDirectory.getTags()) {
					handleExifSubIFDTag(exifSubIFDDirectory, tag, xmpMeta);
				}
			}
			
			GpsDirectory gpsDirectory = metadata.getDirectory(GpsDirectory.class);
			if (gpsDirectory != null) {
				for (Tag tag : gpsDirectory.getTags()) {
					handleGpsTag(gpsDirectory, tag, xmpMeta);
				}
			}
			
			IptcDirectory iptcdirectory = metadata.getDirectory(IptcDirectory.class);
			if (iptcdirectory != null) {
				for (Tag tag : iptcdirectory.getTags()) {
					handleIptcTag(iptcdirectory, tag, xmpMeta);
				}
			}
			if (metadata.hasErrors()) {
				xmpMeta.setProperty(XMPConst.NS_XML, "errors", "yes");
			} else {
				xmpMeta.setProperty(XMPConst.NS_XML, "errors", "no");
			}
			metacard.setMetadata(XMPMetaFactory.serializeToString(xmpMeta, new SerializeOptions()));
		} catch (com.adobe.xmp.XMPException e) {
			LOGGER.warn(e);
			throw new CatalogTransformerException(e);
		} catch (com.drew.metadata.MetadataException e) {
			LOGGER.warn(e);
			throw new CatalogTransformerException(e);
		}
	}
	
	private void handleExifTag(ExifIFD0Directory exifdirectory, Tag tag, XMPMeta xmpMeta) throws com.adobe.xmp.XMPException, com.drew.metadata.MetadataException
	{
		for (XMPMapping map : xmpmapIFD0) {
			if (map.exifTag() == tag.getTagType()) {
				map.addTagValueToXmp(xmpMeta, exifdirectory);
			}
		}
	}
	
	private void handleExifSubIFDTag(ExifSubIFDDirectory exifSubIFDDirectory, Tag tag, XMPMeta xmpMeta) throws com.adobe.xmp.XMPException, com.drew.metadata.MetadataException
	{
		for (XMPMapping map : xmpmapSubIFD) {
			if (map.exifTag() == tag.getTagType()) {
				map.addTagValueToXmp(xmpMeta, exifSubIFDDirectory);
			}
		}
	}
	
	private void handleGpsTag(GpsDirectory gpsDirectory, Tag tag, XMPMeta xmpMeta) throws com.adobe.xmp.XMPException, com.drew.metadata.MetadataException
	{
		for (XMPMapping map : xmpmapgps) {
			if (map.exifTag() == tag.getTagType()) {
				map.addTagValueToXmp(xmpMeta, gpsDirectory);
			}
		}
	}

	private void handleIptcTag(IptcDirectory iptcDirectory, Tag tag, XMPMeta xmpMeta) throws com.adobe.xmp.XMPException, com.drew.metadata.MetadataException
	{
		for (XMPMapping map : xmpmapiptc) {
			if (map.exifTag() == tag.getTagType()) {
				map.addTagValueToXmp(xmpMeta, iptcDirectory);
			}
		}
	}
	
}
