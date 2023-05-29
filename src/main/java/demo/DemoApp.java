package demo;


import demo.entity.Note;
import demo.entity.Person;
import orm.SessionFactory;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

public class DemoApp {

    //todo: create appropriate tests instead of this demo
    public static void main(String[] args) {
        var dataSource = initializeDataSource();
        var sessionfactory = new SessionFactory(dataSource);
        var session = sessionfactory.createSession();

        var person = session.find(Person.class, 3L);
        System.out.println(person);

        var note = session.find(Note.class, 6L);
        System.out.println(note);

        note.setBody("UPDATED BODY!!!!!");
        person.setEmail("MYUPDATED EMAIL");
        person.setFirstName("OLEH!!!!!fd");

        session.close();
    }

    private static DataSource initializeDataSource() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL("jdbc:postgresql:postgres");
        dataSource.setUser("postgres");
        dataSource.setPassword("12345");

        return dataSource;
    }
}
