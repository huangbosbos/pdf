/*
 * Copyright 2006-2017 ICEsoft Technologies Canada Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either * express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.icepdf.core.util.content;

import org.icepdf.core.pobjects.ImageStream;
import org.icepdf.core.pobjects.OptionalContents;
import org.icepdf.core.pobjects.Page;
import org.icepdf.core.pobjects.Resources;
import org.icepdf.core.pobjects.graphics.*;
import org.icepdf.core.pobjects.graphics.commands.GlyphOutlineDrawCmd;
import org.icepdf.core.pobjects.graphics.commands.ImageDrawCmd;
import org.icepdf.core.pobjects.graphics.text.PageText;
import org.icepdf.core.util.Library;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 */
public class NContentParser extends AbstractContentParser {

    private static final Logger logger =
            Logger.getLogger(NContentParser.class.toString());

    /**
     * Inline image cache,  for heavily tiled background images.  Can be cleared
     * between document parses if needed.
     */
    public static Map<String, ImageReference> inlineImageCache =
            Collections.synchronizedMap(new WeakHashMap<String, ImageReference>());

    /**
     * @param l PDF library master object.
     * @param r resources
     */
    public NContentParser(Library l, Resources r) {
        super(l, r);
    }

    /**
     * Parse a pages content stream.
     *
     * @param streamBytes byte stream containing page content
     * @return a Shapes Object containing all the pages text and images shapes.
     * @throws InterruptedException if current parse thread is interrupted.
     * @throws IOException          unexpected end of content stream.
     */
    public ContentParser parse(byte[][] streamBytes, Page page)
            throws InterruptedException, IOException {
        if (shapes == null) {
            shapes = new Shapes();
            // Normal, clean content parse where graphics state is null
            if (graphicState == null) {
                graphicState = new GraphicsState(shapes);
            }
            // If not null we have an Form XObject that contains a content stream
            // and we must copy the previous graphics states draw settings in order
            // preserve colour and fill data for the XOjbects content stream.
            else {
                // the graphics state gets a new coordinate system.
                graphicState.setCTM(new AffineTransform());
                // reset the clipping area.
                graphicState.setClip(null);
                // copy previous stroke info
                setStroke(shapes, graphicState);
                // assign new shapes to the new graphics state
                graphicState.setShapes(shapes);
            }
        }

        if (oCGs == null && library.getCatalog() != null &&
                library.getCatalog().getOptionalContent() != null) {
            oCGs = new LinkedList<OptionalContents>();
        }

        if (logger.isLoggable(Level.FINER)) {
            logger.finer("Parsing page content streams: " + streamBytes.length);
            // print all the stream byte chunks.
            for (byte[] streamByte : streamBytes) {
                if (streamByte != null) {
                    String tmp = new String(streamByte, "ISO-8859-1");
                    logger.finer("Content = " + tmp);
                }
            }
        }
        int count = 0;
        // great a parser to get tokens for stream
        Lexer lexer;

        // test case for progress bar
        lexer = new Lexer();
        lexer.contentStream(streamBytes);

        // text block y offset.
        float yBTstart = 0;

        try {
            // loop through each token returned form the parser
            Object tok;
            while (true) {
                count++;
                tok = lexer.nextToken();
//                if (logger.isLoggable(Level.FINER)){
//                    if (tok instanceof Integer) {
//                        logger.finer(OperandNames.OPP_LOOKUP.get(tok));
//                    } else {
//                        logger.finer(String.valueOf(tok));
//                    }
//                }
                // no more tokens break out.
                if (tok == null) {
                    break;
                }

                // add any names and numbers and every thing else on the
                // stack for future reference
                if (!(tok instanceof Integer)) {
                    stack.push(tok);
                } else {
                    // minimize interrupted checks.
                    if (count % 1000 == 0 && Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("ContentParser thread interrupted");
                    }

                    int operand = (Integer) tok;
                    // Append a straight line segment from the current point to the
                    // point (x, y). The new current point is (x, y).
                    switch (operand) {
                        case OperandNames.OP_l:
                            geometricPath = consume_L(stack, geometricPath);
                            break;

                        // Begin a new subpath by moving the current point to
                        // coordinates (x, y), omitting any connecting line segment. If
                        // the previous path construction operator in the current path
                        // was also m, the new m overrides it; no vestige of the
                        // previous m operation remains in the path.
                        case OperandNames.OP_m:
                            geometricPath = consume_m(stack, geometricPath);
                            break;

                        // Append a cubic Bezier curve to the current path. The curve
                        // extends from the current point to the point (x3, y3), using
                        // (x1, y1) and (x2, y2) as the Bezier control points.
                        // The new current point is (x3, y3).
                        case OperandNames.OP_c:
                            geometricPath = consume_c(stack, geometricPath);
                            break;

                        // Stroke the path
                        case OperandNames.OP_S:
                            geometricPath = consume_S(graphicState, shapes, geometricPath);
                            break;

                        // Font selection
                        case OperandNames.OP_Tf:
                            consume_Tf(graphicState, stack, resources);
                            break;

                        // Begin a text object, initializing the text matrix, Tm, and
                        // the text line matrix, Tlm, to the identity matrix. Text
                        // objects cannot be nested; a second BT cannot appear before
                        // an ET.
                        case OperandNames.OP_BT:
                            // start parseText, which parses until ET is reached
                            try {
                                yBTstart = parseText(lexer, shapes, yBTstart);
                            } catch (Exception e) {
                                logger.log(Level.FINEST, "Error parsing text block", e);
                            }
                            break;

                        // Fill the path, using the nonzero winding number rule to
                        // determine the region to fill (see "Nonzero Winding
                        // Number Rule" ). Any subpaths that are open are implicitly
                        // closed before being filled. f or F
                        case OperandNames.OP_F:
                            geometricPath = consume_F(graphicState, shapes, geometricPath);
                            break;
                        case OperandNames.OP_f:
                            geometricPath = consume_f(graphicState, shapes, geometricPath);
                            break;

                        // Saves Graphics State, should copy the entire  graphics state onto
                        // the graphicsState object's stack
                        case OperandNames.OP_q:
                            graphicState = consume_q(graphicState);
                            break;
                        // Restore Graphics State, should restore the entire graphics state
                        // to its former value by popping it from the stack
                        case OperandNames.OP_Q:
                            graphicState = consume_Q(graphicState, shapes);
                            break;

                        // Append a rectangle to the current path as a complete subpath,
                        // with lower-left corner (x, y) and dimensions width and height
                        // in user space. The operation x y width height re is equivalent to
                        //        x y m
                        //        (x + width) y l
                        //       (x + width) (y + height) l
                        //        x (y + height) l
                        //        h
                        case OperandNames.OP_re:
                            geometricPath = consume_re(stack, geometricPath);
                            break;

                        // Modify the current transformation matrix (CTM) by concatenating the
                        // specified matrix
                        case OperandNames.OP_cm:
                            consume_cm(graphicState, stack, inTextBlock, textBlockBase);
                            break;

                        // Close the current sub path by appending a straight line segment
                        // from the current point to the starting point of the sub path.
                        // This operator terminates the current sub path; appending
                        // another segment to the current path will begin a new subpath,
                        // even if the new segment begins at the endpoint reached by the
                        // h operation. If the current subpath is already closed,
                        // h does nothing.
                        case OperandNames.OP_h:
                            consume_h(geometricPath);
                            break;
                        // Begin a marked-content sequence with an associated property
                        // list, terminated by a balancing EMC operator. tag is a name
                        // object indicating the role or significance of the sequence;
                        // properties is either an inline dictionary containing the
                        // property list or a name object associated with it in the
                        // Properties sub dictionary of the current resource dictionary
                        case OperandNames.OP_BDC:
                            consume_BDC(stack, shapes, oCGs, resources);
                            break;

                        // End a marked-content sequence begun by a BMC or BDC operator.
                        case OperandNames.OP_EMC:
                            consume_EMC(shapes, oCGs);
                            break;

                        // Begin a marked-content sequence terminated by a balancing EMC
                        // operator.tag is a name object indicating the role or
                        // significance of the sequence.
                        case OperandNames.OP_BMC:
                            consume_BMC(stack, shapes, oCGs, resources);
                            break;

                        /**
                         * External Object (XObject) a graphics object whose contents
                         * are defined by a self-contained content stream, separate
                         * from the content stream in which it is used. There are three
                         * types of external object:
                         *
                         *   - An image XObject (Section 4.8.4, "Image Dictionaries")
                         *     represents a sampled visual image such as a photograph.
                         *   - A form XObject (Section 4.9, "Form XObjects") is a
                         *     self-contained description of an arbitrary sequence of
                         *     graphics objects.
                         *   - A PostScript XObject (Section 4.7.1, "PostScript XObjects")
                         *     contains a fragment of code expressed in the PostScript
                         *     page description language. PostScript XObjects are no
                         *     longer recommended to be used. (NOT SUPPORTED)
                         */
                        // Paint the specified XObject. The operand name must appear as
                        // a key in the XObject subdictionary of the current resource
                        // dictionary (see Section 3.7.2, "Resource Dictionaries"); the
                        // associated value must be a stream whose Type entry, if
                        // present, is XObject. The effect of Do depends on the value of
                        // the XObject's Subtype entry, which may be Image , Form, or PS
                        case OperandNames.OP_Do:
                            graphicState = consume_Do(graphicState, stack, shapes,
                                    resources, true, imageIndex, page);
                            break;

                        // Fill the path, using the even-odd rule to determine the
                        // region to fill
                        case OperandNames.OP_f_STAR:
                            geometricPath = consume_f_star(graphicState, shapes, geometricPath);
                            break;

                        // Sets the specified parameters in the graphics state.  The gs operand
                        // points to a name resource which should be a an ExtGState object.
                        // The graphics state parameters in the ExtGState must be concatenated
                        // with the the current graphics state.
                        case OperandNames.OP_gs:
                            consume_gs(graphicState, stack, resources, shapes);
                            break;

                        // End the path object without filling or stroking it. This
                        // operator is a "path-painting no-op," used primarily for the
                        // side effect of changing the current clipping path
                        case OperandNames.OP_n:
                            // clipping path outlines are visible when this is set to null;
                            geometricPath = consume_n(geometricPath);
                            break;

                        // Set the line width in the graphics state
                        case OperandNames.OP_w:
                            consume_w(graphicState, stack, shapes, glyph2UserSpaceScale);
                            break;
                        case OperandNames.OP_LW:
                            consume_w(graphicState, stack, shapes, glyph2UserSpaceScale);
                            break;

                        // Modify the current clipping path by intersecting it with the
                        // current path, using the nonzero winding number rule to
                        // determine which regions lie inside the clipping path.
                        case OperandNames.OP_W:
                            consume_W(graphicState, geometricPath);
                            break;

                        // Fill Color with ColorSpace
                        case OperandNames.OP_sc:
                            consume_sc(graphicState, stack, library, resources, false);
                            break;
                        case OperandNames.OP_scn:
                            consume_sc(graphicState, stack, library, resources, true);
                            break;

                        // Close, fill, and then stroke the path, using the nonzero
                        // winding number rule to determine the region to fill. This
                        // operator has the same effect as the sequence h B. See also
                        // "Special Path-Painting Considerations"
                        case OperandNames.OP_b:
                            geometricPath = consume_b(graphicState, shapes, geometricPath);
                            break;

                        // Same as K, but for non-stroking operations.
                        case OperandNames.OP_k:
                            consume_k(graphicState, stack, library);
                            break;

                        // Same as g but for none stroking operations
                        case OperandNames.OP_g:
                            consume_g(graphicState, stack, library);
                            break;

                        // Sets the flatness tolerance in the graphics state, NOT SUPPORTED
                        // flatness is a number in the range 0 to 100, a value of 0 specifies
                        // the default tolerance
                        case OperandNames.OP_i:
                            consume_i(stack);
                            break;

                        // Miter Limit
                        case OperandNames.OP_M:
                            consume_M(graphicState, stack, shapes);
                            break;

                        // Set the line cap style of the graphic state, related to Line Join
                        // style
                        case OperandNames.OP_J:
                            consume_J(graphicState, stack, shapes);
                            break;

                        // Same as RG, but for non-stroking operations.
                        case OperandNames.OP_rg:
                            consume_rg(graphicState, stack, library);
                            break;

                        // Sets the line dash pattern in the graphics state. A normal line
                        // is [] 0.  See Graphics State -> Line dash patter for more information
                        // in the PDF Reference.  Java 2d uses the same notation so there
                        // is not much work to be done other then parsing the data.
                        case OperandNames.OP_d:
                            consume_d(graphicState, stack, shapes);
                            break;

                        // Append a cubic Bezier curve to the current path. The curve
                        // extends from the current point to the point (x3, y3), using
                        // the current point and (x2, y2) as the Bezier control points.
                        // The new current point is (x3, y3).
                        case OperandNames.OP_v:
                            consume_v(stack, geometricPath);
                            break;

                        // Set the line join style in the graphics state
                        case OperandNames.OP_j:
                            consume_j(graphicState, stack, shapes);
                            break;

                        // Append a cubic Bezier curve to the current path. The curve
                        // extends from the current point to the point (x3, y3), using
                        // (x1, y1) and (x3, y3) as the Bezier control points.
                        // The new current point is (x3, y3).
                        case OperandNames.OP_y:
                            consume_y(stack, geometricPath);
                            break;

                        // Same as CS, but for nonstroking operations.
                        case OperandNames.OP_cs:
                            consume_cs(graphicState, stack, resources);
                            break;

                        // Color rendering intent in the graphics state
                        case OperandNames.OP_ri:
                            stack.pop();
                            break;

                        // Set the color to use for stroking operations in a device, CIE-based
                        // (other than ICCBased), or Indexed color space. The number of operands
                        // required and their interpretation depends on the current stroking color space:
                        //   - For DeviceGray, CalGray, and Indexed color spaces, one operand
                        //     is required (n = 1).
                        //   - For DeviceRGB, CalRGB, and Lab color spaces, three operands are
                        //     required (n = 3).
                        //   - For DeviceCMYK, four operands are required (n = 4).
                        case OperandNames.OP_SC:
                            consume_SC(graphicState, stack, library, resources, false);
                            break;
                        case OperandNames.OP_SCN:
                            consume_SC(graphicState, stack, library, resources, true);
                            break;

                        // Fill and then stroke the path, using the nonzero winding
                        // number rule to determine the region to fill. This produces
                        // the same result as constructing two identical path objects,
                        // painting the first with f and the second with S. Note,
                        // however, that the filling and stroking portions of the
                        // operation consult different values of several graphics state
                        // parameters, such as the current color.
                        case OperandNames.OP_B:
                            geometricPath = consume_B(graphicState, shapes,
                                    geometricPath);
                            break;

                        // Set the stroking color space to DeviceCMYK (or the DefaultCMYK color
                        // space; see "Default Color Spaces" on page 227) and set the color to
                        // use for stroking operations. Each operand must be a number between
                        // 0.0 (zero concentration) and 1.0 (maximum concentration). The
                        // behavior of this operator is affected by the overprint mode
                        // (see Section 4.5.6, "Overprint Control").
                        case OperandNames.OP_K:
                            consume_K(graphicState, stack, library);
                            break;

                        /**
                         * Type3 operators, update the text state with data from these operands
                         */
                        case OperandNames.OP_d0:
                            graphicState = consume_d0(graphicState, stack);
                            break;

                        // Close and stroke the path. This operator has the same effect
                        // as the sequence h S.
                        case OperandNames.OP_s:
                            geometricPath = consume_s(graphicState, shapes, geometricPath);
                            break;

                        // Set the stroking color space to DeviceGray (or the DefaultGray color
                        // space; see "Default Color Spaces" ) and set the gray level to use for
                        // stroking operations. gray is a number between 0.0 (black)
                        // and 1.0 (white).
                        case OperandNames.OP_G:
                            consume_G(graphicState, stack, library);
                            break;

                        // Close, fill, and then stroke the path, using the even-odd
                        // rule to determine the region to fill. This operator has the
                        // same effect as the sequence h B*. See also "Special
                        // Path-Painting Considerations"
                        case OperandNames.OP_b_STAR:
                            geometricPath = consume_b_star(graphicState,
                                    shapes, geometricPath);
                            break;

                        // Set the stroking color space to DeviceRGB (or the DefaultRGB color
                        // space; see "Default Color Spaces" on page 227) and set the color to
                        // use for stroking operations. Each operand must be a number between
                        // 0.0 (minimum intensity) and 1.0 (maximum intensity).
                        case OperandNames.OP_RG:
                            consume_RG(graphicState, stack, library);
                            break;

                        // Set the current color space to use for stroking operations. The
                        // operand name must be a name object. If the color space is one that
                        // can be specified by a name and no additional parameters (DeviceGray,
                        // DeviceRGB, DeviceCMYK, and certain cases of Pattern), the name may be
                        // specified directly. Otherwise, it must be a name defined in the
                        // ColorSpace sub dictionary of the current resource dictionary; the
                        // associated value is an array describing the color space.
                        // <b>Note:</b>
                        // The names DeviceGray, DeviceRGB, DeviceCMYK, and Pattern always
                        // identify the corresponding color spaces directly; they never refer to
                        // resources in the ColorSpace sub dictionary. The CS operator also sets
                        // the current stroking color to its initial value, which depends on the
                        // color space:
                        // <li>In a DeviceGray, DeviceRGB, CalGray, or CalRGB color space, the
                        //     initial color has all components equal to 0.0.</li>
                        // <li>In a DeviceCMYK color space, the initial color is
                        //     [0.0 0.0 0.0 1.0].   </li>
                        // <li>In a Lab or ICCBased color space, the initial color has all
                        //     components equal to 0.0 unless that falls outside the intervals
                        //     specified by the space's Range entry, in which case the nearest
                        //     valid value is substituted.</li>
                        // <li>In an Indexed color space, the initial color value is 0. </li>
                        // <li>In a Separation or DeviceN color space, the initial tint value is
                        //     1.0 for all colorants. </li>
                        // <li>In a Pattern color space, the initial color is a pattern object
                        //     that causes nothing to be painted. </li>
                        case OperandNames.OP_CS:
                            consume_CS(graphicState, stack, resources);
                            break;
                        case OperandNames.OP_d1:
                            // save the stack
                            graphicState = consume_d1(graphicState, stack
                            );
                            break;

                        // Fill and then stroke the path, using the even-odd rule to
                        // determine the region to fill. This operator produces the same
                        // result as B, except that the path is filled as if with f*
                        // instead of f. See also "Special Path-Painting Considerations"
                        case OperandNames.OP_B_STAR:
                            geometricPath = consume_B_star(graphicState, shapes, geometricPath);
                            break;

                        // Begin an inline image object
                        case OperandNames.OP_BI:
                            // start parsing image object, which leads to ID and EI
                            // tokends.
                            //    ID - Begin in the image data for an inline image object
                            //    EI - End an inline image object
                            parseInlineImage(lexer, shapes, page);
                            break;

                        // Begin a compatibility section. Unrecognized operators
                        // (along with their operands) will be ignored without error
                        // until the balancing EX operator is encountered.
                        case OperandNames.OP_BX:
                            break;
                        // End a compatibility section begun by a balancing BX operator.
                        case OperandNames.OP_EX:
                            break;

                        // Modify the current clipping path by intersecting it with the
                        // current path, using the even-odd rule to determine which
                        // regions lie inside the clipping path.
                        case OperandNames.OP_W_STAR:
                            consume_W_star(graphicState, geometricPath);
                            break;

                        /**
                         * Single marked-content point
                         */
                        // Designate a marked-content point with an associated property
                        // list. tag is a name object indicating the role or significance
                        // of the point; properties is either an in line dictionary
                        // containing the property list or a name object associated with
                        // it in the Properties sub dictionary of the current resource
                        // dictionary.
                        case OperandNames.OP_DP:
                            consume_DP(stack);
                            break;
                        // Designate a marked-content point. tag is a name object
                        // indicating the role or significance of the point.
                        case OperandNames.OP_MP:
                            consume_MP(stack);
                            break;

                        // shading operator.
                        case OperandNames.OP_sh:
                            consume_sh(graphicState, stack, shapes,
                                    resources);
                            break;

                        /**
                         * We've seen a couple cases when the text state parameters are written
                         * outside of text blocks, this should cover these cases.
                         */
                        // Character Spacing
                        case OperandNames.OP_Tc:
                            consume_Tc(graphicState, stack);
                            break;
                        // Word spacing
                        case OperandNames.OP_Tw:
                            consume_Tw(graphicState, stack);
                            break;
                        // Text leading
                        case OperandNames.OP_TL:
                            consume_TL(graphicState, stack);
                            break;
                        // Rendering mode
                        case OperandNames.OP_Tr:
                            consume_Tr(graphicState, stack);
                            break;
                        // Horizontal scaling
                        case OperandNames.OP_Tz:
                            consume_Tz(graphicState, stack);
                            break;
                        case OperandNames.OP_Ts:
                            consume_Ts(graphicState, stack);
                            break;
                    }
                }
            }
        } catch (IOException e) {
            logger.finer("End of Content Stream");
        }
        catch (InterruptedException e) {
            logger.log(Level.FINE, "ContentParser thread interrupted");
            throw new InterruptedException("ContentParser thread interrupted");
        }
        catch (Throwable e) {
            logger.log(Level.WARNING, "Error parsing content stream. ", e);
        }
        finally {
            // End of stream set alpha state back to 1.0f, so that other
            // streams aren't applied an incorrect alpha value.
//            setAlpha(shapes, AlphaComposite.SRC_OVER, 1.0f);
        }

        return this;
    }

    /**
     * Specialized method for extracting text from documents.
     *
     * @param source content stream source.
     * @return vector where each entry is the text extracted from a text block.
     */
    public Shapes parseTextBlocks(byte[][] source) throws UnsupportedEncodingException, InterruptedException {

        // great a parser to get tokens for stream
        Lexer parser = new Lexer();
        parser.contentStream(source);
        Shapes shapes = new Shapes();

        if (graphicState == null) {
            graphicState = new GraphicsState(shapes);
        }

        try {
            // loop through each token returned form the parser
            Object tok = parser.nextToken();
            Stack<Object> stack = new Stack<Object>();
            double yBTstart = 0;
            int operand;
            while (tok != null) {
                // add any names and numbers and every thing else on the
                // stack for future reference
                if (tok instanceof Integer) {
                    operand = (Integer) tok;
                    switch (operand) {
                        case OperandNames.OP_BT:
                            // start parseText, which parses until ET is reached
                            yBTstart = parseText(parser, shapes, yBTstart);
                            // free up some memory along the way. we don't need
                            // a full stack consume Tf tokens.
                            stack.clear();
                            break;
                        case OperandNames.OP_Tf:
                            // for malformed core docs we need to consume any font
                            // to ensure we can result toUnicode values.
                            consume_Tf(graphicState, stack, resources);
                            stack.clear();
                            break;
                        case OperandNames.OP_Do:
                            consume_Do(graphicState, stack, shapes, resources, false, null, null);
                            stack.clear();
                            break;
                        case OperandNames.OP_BI:
                            parseInlineImage(parser, shapes, null);
                            break;
                    }
                } else {
                    stack.push(tok);
                }
                tok = parser.nextToken();
            }
            // clear our temporary stack.
            stack.clear();
        } catch (IOException e) {
            // eat the result as it a normal occurrence
            logger.finer("End of Content Stream");
        }
        shapes.contract();
        return shapes;
    }

    /**
     * Parses Text found with in a BT block.
     *
     * @param lexer           parser containing BT tokens
     * @param shapes          container of all shapes for the page content being parsed
     * @param previousBTStart y offset of previous BT definition.
     * @return y offset of the this BT definition.
     * @throws java.io.IOException end of content stream is found
     */
    private float parseText(Lexer lexer, Shapes shapes, double previousBTStart)
            throws IOException, InterruptedException {
        Object nextToken;
        inTextBlock = true;
        // keeps track of previous text placement so that Compatibility and
        // implementation note 57 is respected.  That is text drawn after a TJ
        // must not be less then the previous glyphs coords.
        TextMetrics textMetrics = new TextMetrics();
        textBlockBase = new AffineTransform(graphicState.getCTM());

        // transformation matrix used to cMap core space to drawing space
        graphicState.getTextState().tmatrix = new AffineTransform();
        graphicState.getTextState().tlmatrix = new AffineTransform();
        graphicState.scale(1, -1);

        // get reference to PageText.
        PageText pageText = shapes.getPageText();

        // glyphOutline to support text clipping modes, life span is BT->ET.
        GlyphOutlineClip glyphOutlineClip = new GlyphOutlineClip();

        // start parsing of the BT block
        nextToken = lexer.nextToken();
        int operand;
        while (!(nextToken instanceof Integer && (Integer) nextToken == OperandNames.OP_ET)) {

            if (nextToken instanceof Integer) {
                operand = (Integer) nextToken;
                switch (operand) {
                    // Normal text token, string, hex
                    case OperandNames.OP_Tj:
                        consume_Tj(graphicState, stack, shapes,
                                textMetrics, glyphOutlineClip, oCGs);
                        break;

                    // Character Spacing
                    case OperandNames.OP_Tc:
                        consume_Tc(graphicState, stack);
                        break;

                    // Word spacing
                    case OperandNames.OP_Tw:
                        consume_Tw(graphicState, stack);
                        break;

                    // move to the start of he next line, offset from the start of the
                    // current line by (tx,ty)*tx
                    case OperandNames.OP_Td:
                        consume_Td(graphicState, stack, textMetrics, pageText,
                                previousBTStart, oCGs);
                        break;

                    /**
                     * Tranformation matrix
                     * tm =   |f1 f2 0|
                     *        |f3 f4 0|
                     *        |f5 f6 0|
                     */
                    case OperandNames.OP_Tm:
                        consume_tm(graphicState, stack, textMetrics, pageText,
                                previousBTStart, textBlockBase, oCGs);
                        break;

                    // Font selection
                    case OperandNames.OP_Tf:
                        consume_Tf(graphicState, stack, resources);
                        break;

                    // TJ marks a vector, where.......
                    case OperandNames.OP_TJ:
                        consume_TJ(graphicState, stack, shapes,
                                textMetrics, glyphOutlineClip, oCGs);
                        break;

                    // Move to the start of the next line, offset from the start of the
                    // current line by (tx,ty)
                    case OperandNames.OP_TD:
                        consume_TD(graphicState, stack, textMetrics, pageText, oCGs);
                        break;

                    // Text leading
                    case OperandNames.OP_TL:
                        consume_TL(graphicState, stack);
                        break;

                    // Saves Graphics State, should copy the entire  graphics state onto
                    // the graphicsState object's stack
                    case OperandNames.OP_q:
                        graphicState = consume_q(graphicState);
                        break;
                    // Restore Graphics State, should restore the entire graphics state
                    // to its former value by popping it from the stack
                    case OperandNames.OP_Q:
                        graphicState = consume_Q(graphicState, shapes);
                        break;

                    // Modify the current transformation matrix (CTM) by concatenating the
                    // specified matrix
                    case OperandNames.OP_cm:
                        consume_cm(graphicState, stack, inTextBlock, textBlockBase);
                        break;

                    // Move to the start of the next line
                    case OperandNames.OP_T_STAR:
                        consume_T_star(graphicState, textMetrics, pageText, oCGs);
                        break;
                    case OperandNames.OP_BDC:
                        consume_BDC(stack, shapes,
                                oCGs, resources);
                        break;
                    case OperandNames.OP_EMC:
                        consume_EMC(shapes, oCGs);
                        break;

                    // Sets the specified parameters in the graphics state.  The gs operand
                    // points to a name resource which should be a an ExtGState object.
                    // The graphics state parameters in the ExtGState must be concatenated
                    // with the the current graphics state.
                    case OperandNames.OP_gs:
                        consume_gs(graphicState, stack, resources, shapes);
                        break;

                    // Set the line width in the graphics state
                    case OperandNames.OP_w:
                        consume_w(graphicState, stack, shapes, glyph2UserSpaceScale);
                        break;
                    case OperandNames.OP_LW:
                        consume_w(graphicState, stack, shapes, glyph2UserSpaceScale);
                        break;

                    // Fill Color with ColorSpace
                    case OperandNames.OP_sc:
                        consume_sc(graphicState, stack, library, resources, false);
                        break;
                    case OperandNames.OP_scn:
                        consume_sc(graphicState, stack, library, resources, true);
                        break;

                    // Same as K, but for nonstroking operations.
                    case OperandNames.OP_k:
                        consume_k(graphicState, stack, library);
                        break;

                    // Same as g but for none stroking operations
                    case OperandNames.OP_g:
                        consume_g(graphicState, stack, library);
                        break;

                    // Sets the flatness tolerance in the graphics state, NOT SUPPORTED
                    // flatness is a number in the range 0 to 100, a value of 0 specifies
                    // the default tolerance
                    case OperandNames.OP_i:
                        consume_i(stack);
                        break;

                    // Miter Limit
                    case OperandNames.OP_M:
                        consume_M(graphicState, stack, shapes);
                        break;

                    // Set the line cap style of the graphic state, related to Line Join
                    // style
                    case OperandNames.OP_J:
                        consume_J(graphicState, stack, shapes);
                        break;

                    // Same as RG, but for nonstroking operations.
                    case OperandNames.OP_rg:
                        consume_rg(graphicState, stack, library);
                        break;

                    // Sets the line dash pattern in the graphics state. A normal line
                    // is [] 0.  See Graphics State -> Line dash patter for more information
                    // in the PDF Reference.  Java 2d uses the same notation so there
                    // is not much work to be done other then parsing the data.
                    case OperandNames.OP_d:
                        consume_d(graphicState, stack, shapes);
                        break;

                    // Set the line join style in the graphics state
                    case OperandNames.OP_j:
                        consume_j(graphicState, stack, shapes);
                        break;

                    // Same as CS, but for non-stroking operations.
                    case OperandNames.OP_cs:
                        consume_cs(graphicState, stack, resources);
                        break;

                    // Set the color rendering intent in the graphics state
                    case OperandNames.OP_ri:
                        consume_ri(stack);
                        break;

                    // Set the color to use for stroking operations in a device, CIE-based
                    // (other than ICCBased), or Indexed color space. The number of operands
                    // required and their interpretation depends on the current stroking color space:
                    //   - For DeviceGray, CalGray, and Indexed color spaces, one operand
                    //     is required (n = 1).
                    //   - For DeviceRGB, CalRGB, and Lab color spaces, three operands are
                    //     required (n = 3).
                    //   - For DeviceCMYK, four operands are required (n = 4).
                    case OperandNames.OP_SC:
                        consume_SC(graphicState, stack, library, resources, false);
                        break;
                    case OperandNames.OP_SCN:
                        consume_SC(graphicState, stack, library, resources, true);
                        break;

                    // Set the stroking color space to DeviceCMYK (or the DefaultCMYK color
                    // space; see "Default Color Spaces" on page 227) and set the color to
                    // use for stroking operations. Each operand must be a number between
                    // 0.0 (zero concentration) and 1.0 (maximum concentration). The
                    // behavior of this operator is affected by the overprint mode
                    // (see Section 4.5.6, "Overprint Control").
                    case OperandNames.OP_K:
                        consume_K(graphicState, stack, library);
                        break;

                    // Set the stroking color space to DeviceGray (or the DefaultGray color
                    // space; see "Default Color Spaces" ) and set the gray level to use for
                    // stroking operations. gray is a number between 0.0 (black)
                    // and 1.0 (white).
                    case OperandNames.OP_G:
                        consume_G(graphicState, stack, library);
                        break;

                    // Set the stroking color space to DeviceRGB (or the DefaultRGB color
                    // space; see "Default Color Spaces" on page 227) and set the color to
                    // use for stroking operations. Each operand must be a number between
                    // 0.0 (minimum intensity) and 1.0 (maximum intensity).
                    case OperandNames.OP_RG:
                        consume_RG(graphicState, stack, library);
                        break;
                    case OperandNames.OP_CS:
                        consume_CS(graphicState, stack, resources);
                        break;

                    // Rendering mode
                    case OperandNames.OP_Tr:
                        consume_Tr(graphicState, stack);
                        break;

                    // Horizontal scaling
                    case OperandNames.OP_Tz:
                        consume_Tz(graphicState, stack);
                        break;

                    // Text rise
                    case OperandNames.OP_Ts:
                        consume_Ts(graphicState, stack);
                        break;

                    /**
                     * Begin a compatibility section. Unrecognized operators (along with
                     * their operands) will be ignored without error until the balancing
                     * EX operator is encountered.
                     */
                    case OperandNames.OP_BX:
                        break;
                    // End a compatibility section begun by a balancing BX operator.
                    case OperandNames.OP_EX:
                        break;

                    // Move to the next line and show a text string.
                    case OperandNames.OP_SINGLE_QUOTE:
                        consume_single_quote(graphicState, stack, shapes, textMetrics,
                                glyphOutlineClip, oCGs);
                        break;
                    /**
                     * Move to the next line and show a text string, using aw as the
                     * word spacing and ac as the character spacing (setting the
                     * corresponding parameters in the text state). aw and ac are
                     * numbers expressed in unscaled text space units.
                     */
                    case OperandNames.OP_DOUBLE_QUOTE:
                        consume_double_quote(graphicState, stack, shapes, textMetrics,
                                glyphOutlineClip, oCGs);
                        break;
                }
            }
            // push everything else on the stack for consumptions
            else {
                stack.push(nextToken);
            }

            nextToken = lexer.nextToken();
            if (nextToken == null) {
                break;
            }
        }
        // during a BT -> ET text parse there is a change that we might be
        // in MODE_ADD or MODE_Fill_Add which require that the we push the
        // shapes that make up the clipping path to the shapes stack.  When
        // encountered the path will be used as the current clip.
        if (!glyphOutlineClip.isEmpty()) {
            // set the clips so further clips can use the clip outline
            graphicState.setClip(glyphOutlineClip.getGlyphOutlineClip());
            // add the glyphOutline so the clip can be calculated.
            shapes.add(new GlyphOutlineDrawCmd(glyphOutlineClip));
        }
        graphicState.set(textBlockBase);
        if (nextToken instanceof Integer && (Integer) nextToken == OperandNames.OP_ET) {
            inTextBlock = false;
        }

        return textMetrics.getyBTStart();
    }

    private void parseInlineImage(Lexer p, Shapes shapes, Page page) throws IOException {
        try {
            Object tok;
            HashMap<Object, Object> iih = new HashMap<Object, Object>();
            tok = p.nextToken();
            while (!tok.equals(OperandNames.OP_ID)) {
                if (ImageStream.BPC_KEY.equals(tok)) {
                    tok = ImageStream.BITSPERCOMPONENT_KEY;
                } else if (ImageStream.CS_KEY.equals(tok)) {
                    tok = ImageStream.COLORSPACE_KEY;
                } else if (ImageStream.D_KEY.equals(tok)) {
                    tok = ImageStream.DECODE_KEY;
                } else if (ImageStream.DP_KEY.equals(tok)) {
                    tok = ImageStream.DECODEPARMS_KEY;
                } else if (ImageStream.F_KEY.equals(tok)) {
                    tok = ImageStream.FILTER_KEY;
                } else if (ImageStream.H_KEY.equals(tok)) {
                    tok = ImageStream.HEIGHT_KEY;
                } else if (ImageStream.IM_KEY.equals(tok)) {
                    tok = ImageStream.IMAGEMASK_KEY;
                } else if (ImageStream.I_KEY.equals(tok)) {
                    tok = ImageStream.INDEXED_KEY;
                } else if (ImageStream.W_KEY.equals(tok)) {
                    tok = ImageStream.WIDTH_KEY;
                }
                Object tok1 = p.nextToken();
                iih.put(tok, tok1);
                tok = p.nextToken();
            }
            // For inline images in content streams, we have to use
            //   a byte[], instead of going back to the original file,
            //   to re-get the image data, because the inline image is
            //   only a small part of a content stream, which is also
            //   filtered, and potentially concatenated with other
            //   content streams.
            // Long story short: it's too hard to re-get from PDF file
            // Now, since non-inline-image streams can go back to the
            //   file, we have to fake it as coming from the file ...

            ImageReference imageStreamReference;
            byte[] data = p.getImageBytes();
            if (data.length < 256) {
                String tmpKey = new String(data).concat(graphicState.getFillColor() != null ?
                        graphicState.getFillColor().toString() : "");
                // pepper the key with the fill colour.
                ImageReference imageReference = inlineImageCache.get(tmpKey);
                if (imageReference != null) {
                    imageStreamReference = imageReference;
                } else {
                    // create the image stream
                    ImageStream st = new ImageStream(library, iih, data);
                    imageStreamReference = ImageReferenceFactory.getImageReference(
                            st, resources, graphicState, imageIndex.get(), page);
                    inlineImageCache.put(tmpKey, imageStreamReference);
                }
            } else {
                // create the image stream
                ImageStream st = new ImageStream(library, iih, data);
                imageStreamReference = ImageReferenceFactory.getImageReference(
                        st, resources, graphicState, imageIndex.get(), page);
            }
            // experimental display
//            ImageReference imageStreamReference =
//                    new InlineImageStreamReference(st, graphicState.getFillColor(), resources);
//            ImageUtility.displayImage(imageStreamReference.getImage(), "BI");
            AffineTransform af = new AffineTransform(graphicState.getCTM());
            graphicState.scale(1, -1);
            graphicState.translate(0, -1);
            shapes.add(new ImageDrawCmd(imageStreamReference));
            graphicState.set(af);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            logger.log(Level.FINE, "Error parsing inline image.", e);
        }
    }
}

