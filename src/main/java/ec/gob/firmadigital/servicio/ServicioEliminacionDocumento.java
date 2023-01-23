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
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.EJBException;
import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TimerService;
import javax.sql.DataSource;

/**
 * Servicio para eliminar documentos de la base de datos que no han sido firmados por n minutos o n
 * horas.
 *
 * @author Ricardo Arguello <ricardo.arguello@soportelibre.com>
 */
@Singleton
@Startup
public class ServicioEliminacionDocumento {

    @Resource
    private TimerService timerService;

    @Resource(lookup = "java:/FirmaDigitalDS")
    private DataSource ds;

    private static final Logger logger = Logger.getLogger(ServicioEliminacionDocumento.class.getName());
    //GRANJA DE SERVIDORES EN PRODUCCION - COMENTAR EVITAR ELIMINAR DOCUMENTOS
    @PostConstruct
    public void init() {
        borrarDocumentos();
    }

    //To run every n minutes
    //@Schedule(hour = "*", minute = "*/" + DEFAULT_TIMEOUT_MINUTES, persistent = false)
    //To run on every Monday at 9 am
    @Schedule(dayOfWeek = "Mon", hour = "9", persistent = false)
    //GRANJA DE SERVIDORES EN PRODUCCION - COMENTAR EVITAR ELIMINAR DOCUMENTOS
    public void borrarDocumentos() {
        Connection conn = null;
        Statement st = null;

        try {
            conn = ds.getConnection();
            st = conn.createStatement();
            int n = 0;
            if (Boolean.TRUE.equals(IS_PRODUCTION)) {
                logger.info("Borrando documentos de hace mas de " + DEFAULT_TIMEOUT_HOURS + " horas...");
                n = st.executeUpdate("DELETE FROM documento WHERE fecha < NOW() - INTERVAL '" + DEFAULT_TIMEOUT_HOURS + " hours'");
            } else {
                logger.info("Borrando documentos de hace mas de " + DEFAULT_TIMEOUT_MINUTES + " minutos...");
                n = st.executeUpdate("DELETE FROM documento WHERE fecha < NOW() - INTERVAL '" + DEFAULT_TIMEOUT_MINUTES + " minutes'");
            }

            logger.info("Registros eliminados: " + n);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error al borrar documentos", e);
            throw new EJBException(e);
        } finally {
            if (st != null) {
                try {
                    st.close();
                } catch (SQLException e) {
                }
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }
    }
}
