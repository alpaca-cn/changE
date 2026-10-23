// Verbatim function bodies lifted from three/examples/jsm/loaders/DRACOLoader.js (r186).
// In that file they are module-private inside DRACOWorker (a browser Web Worker), so a
// node process cannot reach them. Re-exporting the identical code lets us drive draco
// in-process and measure REAL decode cost rather than a worker round-trip.
// Only change applied: globalThis[...] -> globalThis[...] (no self in node).
import {
	BufferAttribute,
	Color,
	ColorManagement,
	LinearSRGBColorSpace,
	SRGBColorSpace
} from 'three';
function decodeGeometry( draco, decoder, array, taskConfig ) {

		const attributeIDs = taskConfig.attributeIDs;
		const attributeTypes = taskConfig.attributeTypes;

		let dracoGeometry;
		let decodingStatus;

		const geometryType = decoder.GetEncodedGeometryType( array );

		if ( geometryType === draco.TRIANGULAR_MESH ) {

			dracoGeometry = new draco.Mesh();
			decodingStatus = decoder.DecodeArrayToMesh( array, array.byteLength, dracoGeometry );

		} else if ( geometryType === draco.POINT_CLOUD ) {

			dracoGeometry = new draco.PointCloud();
			decodingStatus = decoder.DecodeArrayToPointCloud( array, array.byteLength, dracoGeometry );

		} else {

			throw new Error( 'THREE.DRACOLoader: Unexpected geometry type.' );

		}

		if ( ! decodingStatus.ok() || dracoGeometry.ptr === 0 ) {

			throw new Error( 'THREE.DRACOLoader: Decoding failed: ' + decodingStatus.error_msg() );

		}

		const geometry = { index: null, attributes: [] };

		// Gather all vertex attributes.
		for ( const attributeName in attributeIDs ) {

			const attributeType = globalThis[ attributeTypes[ attributeName ] ];

			let attribute;
			let attributeID;

			// A Draco file may be created with default vertex attributes, whose attribute IDs
			// are mapped 1:1 from their semantic name (POSITION, NORMAL, ...). Alternatively,
			// a Draco file may contain a custom set of attributes, identified by known unique
			// IDs. glTF files always do the latter, and `.drc` files typically do the former.
			if ( taskConfig.useUniqueIDs ) {

				attributeID = attributeIDs[ attributeName ];
				attribute = decoder.GetAttributeByUniqueId( dracoGeometry, attributeID );

			} else {

				attributeID = decoder.GetAttributeId( dracoGeometry, draco[ attributeIDs[ attributeName ] ] );

				if ( attributeID === - 1 ) continue;

				attribute = decoder.GetAttribute( dracoGeometry, attributeID );

			}

			const attributeResult = decodeAttribute( draco, decoder, dracoGeometry, attributeName, attributeType, attribute );

			if ( attributeName === 'color' ) {

				attributeResult.vertexColorSpace = taskConfig.vertexColorSpace;

			}

			geometry.attributes.push( attributeResult );

		}

		// Add index.
		if ( geometryType === draco.TRIANGULAR_MESH ) {

			geometry.index = decodeIndex( draco, decoder, dracoGeometry );

		}

		draco.destroy( dracoGeometry );

		return geometry;

	}

function decodeIndex( draco, decoder, dracoGeometry ) {

		const numFaces = dracoGeometry.num_faces();
		const numIndices = numFaces * 3;
		const byteLength = numIndices * 4;

		const ptr = draco._malloc( byteLength );
		decoder.GetTrianglesUInt32Array( dracoGeometry, byteLength, ptr );
		const index = new Uint32Array( draco.HEAPF32.buffer, ptr, numIndices ).slice();
		draco._free( ptr );

		return { array: index, itemSize: 1 };

	}

function decodeAttribute( draco, decoder, dracoGeometry, attributeName, TypedArray, attribute ) {

		const count = dracoGeometry.num_points();
		const itemSize = attribute.num_components();
		const dracoDataType = getDracoDataType( draco, TypedArray );

		// Reference: https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html#data-alignment
		const srcByteStride = itemSize * TypedArray.BYTES_PER_ELEMENT;
		const dstByteStride = Math.ceil( srcByteStride / 4 ) * 4;

		const dstStride = dstByteStride / TypedArray.BYTES_PER_ELEMENT;

		const srcByteLength = count * srcByteStride;
		const dstByteLength = count * dstByteStride;

		const ptr = draco._malloc( srcByteLength );
		decoder.GetAttributeDataArrayForAllPoints( dracoGeometry, attribute, dracoDataType, srcByteLength, ptr );

		const srcArray = new TypedArray( draco.HEAPF32.buffer, ptr, srcByteLength / TypedArray.BYTES_PER_ELEMENT );
		let dstArray;

		if ( srcByteStride === dstByteStride ) {

			// THREE.BufferAttribute

			dstArray = srcArray.slice();

		} else {

			// THREE.InterleavedBufferAttribute

			dstArray = new TypedArray( dstByteLength / TypedArray.BYTES_PER_ELEMENT );

			let dstOffset = 0;

			for ( let i = 0, il = srcArray.length; i < il; i ++ ) {

				for ( let j = 0; j < itemSize; j ++ ) {

					dstArray[ dstOffset + j ] = srcArray[ i * itemSize + j ];

				}

				dstOffset += dstStride;

			}

		}

		draco._free( ptr );

		return {
			name: attributeName,
			count: count,
			itemSize: itemSize,
			array: dstArray,
			stride: dstStride
		};

	}

function getDracoDataType( draco, TypedArray ) {

		switch ( TypedArray ) {

			case Float32Array: return draco.DT_FLOAT32;
			case Int8Array: return draco.DT_INT8;
			case Int16Array: return draco.DT_INT16;
			case Int32Array: return draco.DT_INT32;
			case Uint8Array: return draco.DT_UINT8;
			case Uint16Array: return draco.DT_UINT16;
			case Uint32Array: return draco.DT_UINT32;

		}

	}

export { decodeGeometry };
