/*
 * Firma Digital: Servicio
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ec.gob.firmadigital.servicio;

import static ec.gob.firmadigital.servicio.token.TokenTimeout.DEFAULT_TIMEOUT_HOURS;
import static ec.gob.firmadigital.servicio.token.TokenTimeout.DEFAULT_TIMEOUT_MINUTES;
import static ec.gob.firmadigital.servicio.token.TokenTimeout.IS_PRODUCTION;

import java.io.IOException;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.validation.constraints.NotNull;

import ec.gob.firmadigital.servicio.model.Documento;
import ec.gob.firmadigital.servicio.pdf.ServicioValidacionPdf;
import ec.gob.firmadigital.servicio.token.ServicioToken;
import ec.gob.firmadigital.servicio.token.TokenExpiradoException;
import ec.gob.firmadigital.servicio.token.TokenInvalidoException;
import ec.gob.firmadigital.servicio.token.TokenTimeout;
import ec.gob.firmadigital.servicio.util.Base64InvalidoException;
import ec.gob.firmadigital.servicio.util.FileUtil;
import io.rubrica.exceptions.CertificadoInvalidoException;
import io.rubrica.exceptions.DocumentoException;
import io.rubrica.exceptions.InvalidFormatException;
import io.rubrica.sign.SignInfo;
import io.rubrica.sign.Signer;
import io.rubrica.sign.pdf.PDFSigner;
import io.rubrica.utils.FileUtils;
import io.rubrica.utils.Utils;
import java.io.File;

/**
 * Servicio para almacenar, actualizar y obtener documentos desde los sistemas
 * transversales y la aplicación en firmadigital-api
 *
 * @author Ricardo Arguello <ricardo.arguello@soportelibre.com>
 */
@Stateless
public class ServicioDocumento {

    @EJB
    private ServicioToken servicioToken;

    @EJB
    private ServicioSistemaTransversal servicioSistemaTransversal;

    @EJB
    private ServicioValidacionPdf servicioValidacionPdf;

    @EJB
    private ServicioLog servicioLog;

    @PersistenceContext
    private EntityManager em;

    private static final Logger logger = Logger.getLogger(ServicioDocumento.class.getName());

    /**
     * Crea documentos en el sistema, para ser firmados por un cliente.
     *
     * @param cedula
     * @param nombreSistema
     * @param archivos
     * @return
     * @throws ec.gob.firmadigital.servicio.util.Base64InvalidoException
     */
    public String crearDocumentos(@NotNull String cedula, @NotNull String nombreSistema,
            @NotNull Map<String, String> archivos) throws Base64InvalidoException {

        // Verificar si existe el sistema
        servicioSistemaTransversal.buscarSistema(nombreSistema);

        List<String> ids = new ArrayList<>();

        for (String nombre : archivos.keySet()) {
            String archivo = archivos.get(nombre);

            // Crear nuevo documento
            Documento documento = new Documento();
            documento.setCedula(cedula);
            documento.setNombre(nombre);
            documento.setFecha(new Date());
            documento.setSistema(nombreSistema);
            documento.setArchivo(decodificarBase64(archivo));

            // Almacenar
            em.persist(documento);

            // Agregar a la lista de Ids
            ids.add(documento.getId().toString());
        }

        Map<String, Object> parametros = new HashMap<>();
        parametros.put("cedula", cedula);
        parametros.put("sistema", nombreSistema);
        parametros.put("ids", String.join(",", ids));

        // Expiracion del Token
        Date expiracion = null;
        if (Boolean.TRUE.equals(IS_PRODUCTION)) {
            expiracion = TokenTimeout.addHours(new Date(), DEFAULT_TIMEOUT_HOURS);
        } else {
            expiracion = TokenTimeout.addMinutes(new Date(), DEFAULT_TIMEOUT_MINUTES);
        }

        // Retorna el Token
        return servicioToken.generarToken(parametros, expiracion);
    }

    /**
     * Obtiene un documento mediante un token.
     *
     * @param token
     * @return
     * @throws TokenInvalidoException
     * @throws TokenExpiradoException
     */
    public Map<Long, String> obtenerDocumentos(String token) throws TokenInvalidoException, TokenExpiradoException {
        Map<String, Object> parametros = servicioToken.parseToken(token);
        String ids = (String) parametros.get("ids");
        logger.fine("ids=" + ids);

        Map<Long, String> archivos = new HashMap<>();

        for (String id : convertirEnList(ids)) {
            Long primaryKey = Long.parseLong(id);
            Documento documento = em.find(Documento.class, primaryKey);
            String archivo = codificarBase64(documento.getArchivo());
            archivos.put(primaryKey, archivo);
        }

        return archivos;
    }

    /**
     *
     * @param token
     * @param archivos
     * @param cedulaJson
     * @return
     * @throws ec.gob.firmadigital.servicio.token.TokenInvalidoException
     * @throws ec.gob.firmadigital.servicio.CedulaInvalidaException
     * @throws ec.gob.firmadigital.servicio.token.TokenExpiradoException
     * @throws ec.gob.firmadigital.servicio.util.Base64InvalidoException
     * @throws ec.gob.firmadigital.servicio.CertificadoRevocadoException
     * @throws ec.gob.firmadigital.servicio.DocumentoNoExisteException
     */
    public int actualizarDocumentos(String token, Map<Long, String> archivos, String cedulaJson)
            throws TokenInvalidoException, CedulaInvalidaException, TokenExpiradoException, Base64InvalidoException,
            CertificadoRevocadoException, DocumentoNoExisteException {
        
        Map<String, Object> parametros = servicioToken.parseToken(token);

        String ids = (String) parametros.get("ids");
        logger.info("ids=" + ids);

        String cedulaToken = (String) parametros.get("cedula");
        logger.info("cedulaToken=" + FileUtil.hashMD5(cedulaToken));
        logger.info("cedulaJson=" + FileUtil.hashMD5(cedulaJson));

        if (!cedulaToken.equals(cedulaJson)) {
            throw new CedulaInvalidaException("La cedula " + cedulaJson + " es incorrecta");
        }

        String nombreSistema = (String) parametros.get("sistema");
        URL url = servicioSistemaTransversal.buscarUrlSistema(nombreSistema);
        logger.info("sistema=" + nombreSistema);

        List<String> idList = convertirEnList(ids);

        if (idList.size() != archivos.size()) {
            throw new IllegalArgumentException("El token contiene " + idList.size()
                    + " archivos por procesar pero se enviaron solo " + archivos.size() + " archivos!");
        }

        int documentosFirmados = 0;

        for (String id : idList) {
            Long primaryKey = Long.parseLong(id);
            String archivoBase64 = archivos.get(primaryKey);

            if (archivoBase64 == null) {
                throw new IllegalArgumentException(
                        "El token contiene una lista de archivos distinta a los archivos solicitados para actualizar: "
                        + ids);
            }

            // Actualizar el archivo
            Documento documento = em.find(Documento.class, primaryKey);

            if (documento == null) {
                logger.warning("El documento " + primaryKey + " no existe en la base de datos");
                throw new DocumentoNoExisteException("El documento " + primaryKey + " no existe en la base de datos");
            }

            byte[] archivo = decodificarBase64(archivoBase64);

            // Obtener el nombre del firmante para almacenar el documento en el
            // sistema transversal
            io.rubrica.certificate.to.Documento documentoTo = null;

            try {
                File file = null;
                try {
                    file = FileUtils.byteArrayConvertToFile(archivo);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Error al crear el archivo temporal");
                }
                // Se valida la extension del archivo
               String mimeType = FileUtil.getMimeType(archivo);
                if (mimeType.contains("pdf")) {
                    documentoTo = Utils.pdfToDocumento(file);
                }
                if (mimeType.contains("xml")) {
//                    Signer docSigner = Utils.documentSigner(file);
//                    documentoTo = Utils.signInfosToCertificados(docSigner.getSigners(archivo));
                }
            } catch (InvalidFormatException | IOException e) {
                throw new IllegalArgumentException("Error en la verificacion de firma", e);
            } catch (DocumentoException ex) {
                Logger.getLogger(ServicioDocumento.class.getName()).log(Level.SEVERE, null, ex);
            } catch (CertificadoInvalidoException ex) {
                Logger.getLogger(ServicioDocumento.class.getName()).log(Level.SEVERE, null, ex);
            } catch (Exception ex) {
                Logger.getLogger(ServicioDocumento.class.getName()).log(Level.SEVERE, null, ex);
            }

            try {
               String apiKeyRest = servicioSistemaTransversal.buscarApiKeyRest(nombreSistema);
                if (apiKeyRest != null) {
                    servicioSistemaTransversal.almacenarDocumentoREST(documentoTo, documento.getCedula(), documento.getNombre(),
                            archivoBase64, url, apiKeyRest);
                } else {
                    String datosFirmante = "";
                    X509Certificate certificado = null;
	             String mimeType = FileUtil.getMimeType(archivo);
                    if (mimeType.contains("pdf")) {
		         datosFirmante = servicioValidacionPdf.getNombre(archivo);
                        Signer signer = new PDFSigner();
                        List<SignInfo> singInfos = signer.getSigners(archivo);
                        SignInfo firma = singInfos.get(0);
                        certificado = firma.getCerts()[0];
                    }
                    servicioSistemaTransversal.almacenarDocumento(documento.getCedula(), documento.getNombre(),
                            archivoBase64, datosFirmante, url, certificado);
                }
                documentosFirmados++;

                logger.info("Documento enviado al sistema " + nombreSistema);
                servicioLog.info("ServicioDocumento::actualizarDocumentos",
                        "Documento enviado al sistema " + nombreSistema
                        + ", firmado por " + FileUtil.hashMD5(cedulaToken)
                        + ", tamano documento (bytes) " + documento.getArchivo().length);
            } catch (SistemaTransversalException e) {
                String mensajeError = "No se pudo enviar el documento al sistema " + nombreSistema;
                servicioLog.error("ServicioDocumento::actualizarDocumentos", mensajeError);
                logger.log(Level.SEVERE, mensajeError);
            } catch (InvalidFormatException | IOException e) {
                throw new IllegalArgumentException("Error al obtener información del firmante", e);
            }

            // Eliminar el documento
            em.remove(documento);
        }

        return documentosFirmados;
    }

    /**
     * Convierte una cadena de texto con una lista separada por comas de ints en
     * una List.
     *
     * @param ids
     * @return
     */
    private List<String> convertirEnList(String ids) {
        // Separar por "espacio en blanco, coma, espacio en blanco":
        return Arrays.asList(ids.split("\\s*,\\s*"));
    }

    private byte[] decodificarBase64(String base64) throws Base64InvalidoException {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new Base64InvalidoException(e);
        }
    }

    private String codificarBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
